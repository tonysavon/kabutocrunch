/*
 * Strict decoders for Kabuto raw-high and classic Dali streams.
 *
 * Test-only Java port of src/decode.c; not included in the plugin JAR.
 */
package kabutocrunch;

public final class Decoder {

    private Decoder() {
    }

    /** Result of a decode attempt. */
    public static final class Result {
        public final boolean ok;
        public final int outSize;
        public final String error;

        Result(boolean ok, int outSize, String error) {
            this.ok = ok;
            this.outSize = outSize;
            this.error = error;
        }
    }

    private static final class Dc {
        byte[] src;
        int srcPos;
        int srcSize;
        byte[] dst;
        int dstPos;
        int dstSize;
        int bitMask;
        int bitByte;
        String error;
    }

    private static boolean fail(Dc decoder, String message) {
        if (decoder.error == null) {
            decoder.error = message;
        }
        return false;
    }

    private static boolean readByte(Dc decoder, int[] value) {
        if (decoder.srcPos >= decoder.srcSize) {
            return fail(decoder, "truncated compressed stream");
        }
        value[0] = decoder.src[decoder.srcPos++] & 0xff;
        return true;
    }

    private static final class BitReader implements KcFormat.GetBit {
        private final Dc decoder;

        BitReader(Dc decoder) {
            this.decoder = decoder;
        }

        @Override
        public int getBit(int[] value) {
            int[] byteValue = new int[1];
            if ((decoder.bitMask >>= 1) == 0) {
                decoder.bitMask = 0x80;
                if (!readByte(decoder, byteValue)) {
                    return 0;
                }
                decoder.bitByte = byteValue[0];
            }
            value[0] = (decoder.bitByte & decoder.bitMask) != 0 ? 1 : 0;
            return 1;
        }
    }

    private static boolean readBit(Dc decoder, BitReader reader, int[] value) {
        return reader.getBit(value) != 0;
    }

    private static boolean readLength(Dc decoder, BitReader reader, int firstZeroConsumed, int[] value) {
        int[] box = new int[1];
        if (!KcFormat.readDaliLength(firstZeroConsumed, reader, box)) {
            if (decoder.error == null) {
                decoder.error = "invalid or overflowing length code";
            }
            return false;
        }
        if (box[0] == 0 || box[0] > KcFormat.MAX_VARLEN) {
            return fail(decoder, "length is outside the supported range");
        }
        value[0] = box[0];
        return true;
    }

    private static boolean copyLiterals(Dc decoder, int length) {
        int[] byteValue = new int[1];

        if (length > (decoder.dstSize - decoder.dstPos)) {
            return fail(decoder, "literal run exceeds output capacity");
        }
        if (length > (decoder.srcSize - decoder.srcPos)) {
            return fail(decoder, "truncated literal run");
        }
        for (int i = 0; i < length; i++) {
            if (!readByte(decoder, byteValue)) {
                return false;
            }
            decoder.dst[decoder.dstPos++] = (byte) byteValue[0];
        }
        return true;
    }

    private static boolean copyMatch(Dc decoder, int offset, int length) {
        if (offset < KcFormat.MIN_OFFSET || offset > KcFormat.MAX_OFFSET || offset > decoder.dstPos) {
            return fail(decoder, "invalid match offset " + offset + " at output " + decoder.dstPos
                + ", input " + decoder.srcPos);
        }
        if (length > (decoder.dstSize - decoder.dstPos)) {
            return fail(decoder, "match exceeds output capacity");
        }

        int source = decoder.dstPos - offset;
        for (int i = 0; i < length; i++) {
            decoder.dst[decoder.dstPos++] = decoder.dst[source + i];
        }
        return true;
    }

    private static final int OFFSET_CONTINUE = 0;
    private static final int OFFSET_DONE = 1;
    private static final int OFFSET_FAIL = 2;

    private static int handleNewOffset(Dc decoder, BitReader reader, int[] lastOffset, boolean daliMode) {
        int offsetHigh;
        int offset;
        int metadata;
        int lengthValue;
        int[] box = new int[1];

        if (daliMode) {
            if (!KcFormat.readDaliLength(0, reader, box)) {
                if (decoder.error == null) {
                    decoder.error = "invalid or truncated Dali offset code";
                }
                return OFFSET_FAIL;
            }
            int daliOffsetHigh = box[0];
            if (daliOffsetHigh == 256) {
                return OFFSET_DONE;
            }
            if (daliOffsetHigh == 0 || daliOffsetHigh > 255) {
                fail(decoder, "invalid Dali offset high value");
                return OFFSET_FAIL;
            }
            offsetHigh = daliOffsetHigh - 1;
        } else {
            int shortOffset;
            if (!readBit(decoder, reader, box)) {
                return OFFSET_FAIL;
            }
            shortOffset = box[0];
            if (shortOffset != 0) {
                offsetHigh = 0;
            } else if (!readByte(decoder, box)) {
                return OFFSET_FAIL;
            } else if (box[0] == 0) {
                return OFFSET_DONE;
            } else {
                offsetHigh = box[0];
            }
        }

        if (!readByte(decoder, box)) {
            if (decoder.error == null) {
                decoder.error = "invalid offset high byte";
            }
            return OFFSET_FAIL;
        }
        metadata = box[0];

        offset = offsetHigh * 128 + (metadata >>> 1) + 1;
        if ((metadata & 1) != 0) {
            lengthValue = 1;
        } else if (!readLength(decoder, reader, 1, box)) {
            return OFFSET_FAIL;
        } else {
            lengthValue = box[0];
        }
        if (lengthValue >= KcFormat.MAX_VARLEN || !copyMatch(decoder, offset, lengthValue + 1)) {
            return OFFSET_FAIL;
        }
        lastOffset[0] = offset;
        return OFFSET_CONTINUE;
    }

    private static Result decodeFormat(byte[] packed, int packedSize, byte[] output, int outputCap,
            boolean daliMode) {
        Dc decoder = new Dc();
        int state = 0;
        int lastOffset = 1;

        if (packed == null || packedSize <= 0 || output == null || outputCap < 0 ||
                packedSize > packed.length || outputCap > output.length) {
            return new Result(false, 0, "invalid decoder arguments or empty stream");
        }

        decoder.src = packed;
        decoder.srcPos = 0;
        decoder.srcSize = packedSize;
        decoder.dst = output;
        decoder.dstPos = 0;
        decoder.dstSize = outputCap;
        decoder.bitMask = 0;
        decoder.bitByte = 0;
        decoder.error = null;

        BitReader reader = new BitReader(decoder);
        int[] box = new int[1];
        int[] lastOffsetBox = new int[1];
        lastOffsetBox[0] = lastOffset;

        while (true) {
            int command;

            if (state == 0 || state == 2) {
                if (state == 2) {
                    if (!readBit(decoder, reader, box)) {
                        break;
                    }
                    command = box[0];
                    if (command != 0) {
                        int result = handleNewOffset(decoder, reader, lastOffsetBox, daliMode);
                        if (result == OFFSET_DONE) {
                            return new Result(true, decoder.dstPos, null);
                        }
                        if (result == OFFSET_FAIL) {
                            break;
                        }
                        state = 2;
                    continue;
                    }
                }
                if (!readLength(decoder, reader, 0, box) || !copyLiterals(decoder, box[0])) {
                    break;
                }
                state = 1;
            } else {
                if (!readBit(decoder, reader, box)) {
                    break;
                }
                command = box[0];
                if (command != 0) {
                    int result = handleNewOffset(decoder, reader, lastOffsetBox, daliMode);
                    if (result == OFFSET_DONE) {
                        return new Result(true, decoder.dstPos, null);
                    }
                    if (result == OFFSET_FAIL) {
                        break;
                    }
                    state = 2;
                    continue;
                }
                if (!readLength(decoder, reader, 0, box) || !copyMatch(decoder, lastOffsetBox[0], box[0])) {
                    break;
                }
                state = 2;
            }
        }

        return new Result(false, decoder.dstPos,
            decoder.error != null ? decoder.error : "malformed stream");
    }

    /** Kabuto raw-high stream. */
    public static Result decode(byte[] packed, int packedSize, byte[] output, int outputCap) {
        return decodeFormat(packed, packedSize, output, outputCap, false);
    }

    /** Classic Dali stream. */
    public static Result decodeDali(byte[] packed, int packedSize, byte[] output, int outputCap) {
        return decodeFormat(packed, packedSize, output, outputCap, true);
    }
}
