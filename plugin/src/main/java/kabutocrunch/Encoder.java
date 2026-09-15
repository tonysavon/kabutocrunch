/* Kabutocrunch plugin encoder facade. Copyright (c) 2026 Kabutocrunch contributors.
 * Distributed under the zlib license; see LICENSE. */
package kabutocrunch;

import java.util.Arrays;

/** In-memory encoder used by the KickAssembler modifier. No CLI or file I/O. */
public final class Encoder {
    public static final int MAX_INPUT = 65535;

    private Encoder() { }

    public static byte[] compress(byte[] input, boolean dali, int speed) {
        if (input == null || input.length == 0 || input.length > MAX_INPUT) {
            throw new IllegalArgumentException("payload must contain 1..65535 bytes");
        }
        KcShrink encoder = new KcShrink(speed);
        byte[] packed = new byte[(int) encoder.getMaxCompressedSize(input.length)];
        int flags = KcShrink.FLG_IS_INVERTED | (dali ? KcShrink.FLG_IS_DALI : 0);
        long size = encoder.compress(input, input.length, packed, packed.length,
                KcSearch.baseline(), flags, 0, 0, null);
        if (size < 0 || size > packed.length) {
            throw new IllegalArgumentException("compression failed");
        }
        return Arrays.copyOf(packed, (int) size);
    }
}
