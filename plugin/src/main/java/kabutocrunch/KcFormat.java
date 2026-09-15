/*
 * Kabuto stream constants and Dali-derived Elias length helpers.
 *
 * Java port of src/kc_format.c. Behaviour is bit-exact with the C encoder;
 * see plugin/README.md and tools/test_plugin.py.
 */
package kabutocrunch;

final class KcFormat {

    public static final int MIN_OFFSET = 1;
    public static final int MAX_OFFSET = 0x7f80;
    public static final int MAX_VARLEN = 0xffff;
    public static final int BLOCK_SIZE = 0x10000;
    public static final int MIN_MATCH_SIZE = 1;

    private KcFormat() {
    }

    /** Interface mirroring kc_put_bit_fn: returns 0 to signal failure. */
    public interface PutBit {
        int putBit(int bit);
    }

    /** Interface mirroring kc_get_bit_fn: returns 0 to signal failure. */
    public interface GetBit {
        int getBit(int[] bit);
    }

    /** floor(log2(value)); caller must not pass 0. */
    public static int floorLog2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }

    public static int daliLengthBitCost(int value) {
        if (value == 0) {
            return 0;
        }
        return (floorLog2(value) << 1) + 1;
    }

    private static final class LengthWriter {
        final PutBit putBit;
        int skip;

        LengthWriter(PutBit putBit) {
            this.putBit = putBit;
        }
    }

    private static int emitBit(LengthWriter writer, int bit) {
        if (writer.skip != 0) {
            writer.skip = 0;
            return 1;
        }
        return writer.putBit.putBit(bit & 1);
    }

    public static int writeDaliLength(int value, int skipFirstBit, PutBit putBit) {
        if (value == 0 || putBit == null) {
            return 0;
        }

        LengthWriter writer = new LengthWriter(putBit);
        writer.skip = skipFirstBit != 0 ? 1 : 0;

        int bits = floorLog2(value);
        int marker = 1 << bits;
        if (bits >= 8) {
            value &= ~marker;
            value = (value >>> 8) | ((value & 0xff) << (bits - 8));
        }
        while ((marker >>>= 1) != 0) {
            if (emitBit(writer, 0) == 0 || emitBit(writer, (value & marker) != 0 ? 1 : 0) == 0) {
                return 0;
            }
        }
        return emitBit(writer, 1);
    }

    private static boolean readOne(GetBit getBit, int[] bit) {
        return getBit != null && getBit.getBit(bit) != 0;
    }

    public static boolean readDaliLength(int firstZeroConsumed, GetBit getBit, int[] result) {
        if (getBit == null || result == null) {
            return false;
        }
        int value = 1;
        int control;
        int payload;
        int bits;

        int[] box = new int[1];
        if (firstZeroConsumed != 0) {
            control = 0;
        } else {
            if (!readOne(getBit, box)) {
                return false;
            }
            control = box[0];
        }
        while (control == 0) {
            if (!readOne(getBit, box)) {
                return false;
            }
            payload = box[0];
            if (value > (Integer.MAX_VALUE >>> 1)) {
                return false;
            }
            value = (value << 1) | payload;
            if (!readOne(getBit, box)) {
                return false;
            }
            control = box[0];
        }
        bits = floorLog2(value);
        if (bits >= 8) {
            int payloadMask = (1 << bits) - 1;
            int encoded = value & payloadMask;
            int lowEight = encoded >>> (bits - 8);
            int restMask = (1 << (bits - 8)) - 1;
            int rest = encoded & restMask;
            value = (1 << bits) | (rest << 8) | lowEight;
        }
        result[0] = value;
        return true;
    }
}
