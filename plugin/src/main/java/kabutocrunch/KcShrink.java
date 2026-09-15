/*
 * shrink.c - compressor implementation
 *
 * Copyright (C) 2021 Emmanuel Marty
 *
 * This software is provided 'as-is', without any express or implied
 * warranty.  In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */
/*
 * Salvador-derived optimal parser / encoder core.
 *
 * Java port of src/kc_shrink.c. Integer arithmetic, array indexing, arrival
 * pruning order and every parse decision mirror the C implementation so that
 * the emitted bitstream is byte-identical.
 */
package kabutocrunch;

final class KcShrink {

    public static final int LCP_BITS = 18;
    public static final int TAG_BITS = 4;
    public static final int LCP_MAX = (1 << (LCP_BITS - TAG_BITS)) - 1;
    public static final int LCP_AND_TAG_MAX = (1 << LCP_BITS) - 1;
    public static final int LCP_SHIFT = 63 - LCP_BITS;
    public static final long LCP_MASK = ((1L << LCP_BITS) - 1L) << LCP_SHIFT;
    public static final long POS_MASK = (1L << LCP_SHIFT) - 1L;
    public static final long VISITED_FLAG = 0x8000000000000000L;
    public static final long EXCL_VISITED_MASK = 0x7fffffffffffffffL;

    public static final int FLG_IS_INVERTED = 1;
    public static final int FLG_IS_BACKWARD = 2;
    public static final int FLG_IS_DALI = 4;

    public static final int MIN_ENCODED_MATCH_SIZE = 2;

    private final int tokenCost;

    KcShrink(int speed) {
        if (speed < 0 || speed > 15) {
            throw new IllegalArgumentException("speed must be in 0..15");
        }
        tokenCost = speed + 1;
    }

    /* ------------------------------------------------------------------ */
    /* Statistics (port of salvador_stats)                                 */
    /* ------------------------------------------------------------------ */

    public static final class Stats {
        public int numLiterals;
        public int numNormalMatches;
        public int numRepMatches;
        public int numEod;

        public int safeDist;

        public int minLiterals;
        public int maxLiterals;
        public int totalLiterals;
        public int nonzeroLiteralRuns;

        public int minOffset;
        public int maxOffset;
        public long totalOffsets;

        public int minMatchLen;
        public int maxMatchLen;
        public int totalMatchLens;

        public int minRle1Len;
        public int maxRle1Len;
        public int totalRle1Lens;

        public int minRle2Len;
        public int maxRle2Len;
        public int totalRle2Lens;

        public int commandsDivisor;
        public int literalsDivisor;
        public int matchDivisor;
        public int rle1Divisor;
        public int rle2Divisor;
    }

    /* ------------------------------------------------------------------ */
    /* Compression context (port of salvador_compressor)                   */
    /* ------------------------------------------------------------------ */

    public static final class Compressor {
        int[] sa;
        long[] intervals;
        long[] posData;
        int[] posDataInt;
        long[] openIntervals;

        int[] matchLen;
        int[] matchOff;
        int[] matchDepth;
        int[] bestLen;
        int[] bestOff;

        int[] arrCost;
        int[] arrFromPos;
        int[] arrFromSlot;
        int[] arrRepOffset;
        int[] arrRepPos;
        int[] arrMatchLen;
        int[] arrNumLiterals;
        int[] arrScore;

        int[] firstOffsetForByte;
        int[] nextOffsetForPos;
        int[] offsetCache;
        int[] repMatchArrivalIdx;

        int[] rleLen;
        int[] visited;

        int flags;
        int blockSize;
        int maxOffset;
        int maxArrivalsPerPosition;
        KcSearch.Config search;
        KcSearch.Diagnostics diagnostics = new KcSearch.Diagnostics();
        boolean collectDiagnostics;
        Stats stats = new Stats();
    }

    /* ------------------------------------------------------------------ */
    /* Mutable bit-writer state (replaces the C int* pair)                 */
    /* ------------------------------------------------------------------ */

    static final class BitState {
        int curBitsOffset;
        int curBitShift;
    }

    /** Output buffer view: element i of the C block buffer is out[base + i]. */
    static final class Out {
        final byte[] data;

        Out(byte[] data) {
            this.data = data;
        }
    }

    int offsetBitCost(Compressor compressor, int offset) {
        if ((compressor.flags & FLG_IS_DALI) != 0) {
            return 7 + KcFormat.daliLengthBitCost(((offset - 1) >> 7) + 1);
        }
        return offset <= 128 ? 8 : 16;
    }

    int writeBit(Out out, int base, int outOffset, int maxOutDataSize, int value, BitState bits) {
        if (outOffset >= 0) {
            if (bits.curBitShift < 0) {
                if (outOffset >= maxOutDataSize) {
                    return -1;
                }
                bits.curBitsOffset = outOffset;
                bits.curBitShift = 7;
                out.data[base + outOffset++] = 0;
            }

            if (value != 0) {
                out.data[base + bits.curBitsOffset] |= (byte) (1 << bits.curBitShift);
            }
            bits.curBitShift--;
        }

        return outOffset;
    }

    private final class BitWriter implements KcFormat.PutBit {
        final Out out;
        final int base;
        final int maxSize;
        final BitState bits;
        int outOffset;

        BitWriter(Out out, int base, int maxSize, BitState bits, int outOffset) {
            this.out = out;
            this.base = base;
            this.maxSize = maxSize;
            this.bits = bits;
            this.outOffset = outOffset;
        }

        @Override
        public int putBit(int bit) {
            outOffset = writeBit(out, base, outOffset, maxSize, bit != 0 ? 1 : 0, bits);
            return outOffset >= 0 ? 1 : 0;
        }
    }

    int writeLengthValue(Out out, int base, int outOffset, int maxOutDataSize, int value,
            int skip, BitState bits) {
        if (value <= 0) {
            return -1;
        }
        BitWriter writer = new BitWriter(out, base, maxOutDataSize, bits, outOffset);
        if (KcFormat.writeDaliLength(value, skip, writer) == 0) {
            return -1;
        }
        return writer.outOffset;
    }

    int limitMatchLenLocal(int length) {
        return length;
    }

    int getLiteralsVarlenSize(int length) {
        return length > 0 ? tokenCost + KcFormat.daliLengthBitCost(length) : 0;
    }

    int getMatchVarlenSizeNoRep(int length) {
        return KcFormat.daliLengthBitCost(length - 1);
    }

    int getMatchVarlenSizeRep(int length) {
        return KcFormat.daliLengthBitCost(length);
    }

    /* ------------------------------------------------------------------ */
    /* Bitstream writer                                                    */
    /* ------------------------------------------------------------------ */

    int writeLiterals(Compressor compressor, byte[] inWindow, int literalOffset, int numLiterals,
            Out out, int base, int maxOutDataSize, int outOffset, BitState bits,
            int[] isFirstCommand) {
        if (numLiterals > KcFormat.MAX_VARLEN) {
            return -1;
        }
        if (numLiterals <= 0) {
            return outOffset;
        }

        Stats stats = compressor.stats;
        if (numLiterals < stats.minLiterals || stats.minLiterals == -1) {
            stats.minLiterals = numLiterals;
        }
        if (numLiterals > stats.maxLiterals) {
            stats.maxLiterals = numLiterals;
        }
        stats.totalLiterals += numLiterals;
        stats.literalsDivisor++;

        if (isFirstCommand[0] == 0) {
            outOffset = writeBit(out, base, outOffset, maxOutDataSize, 0 /* literals follow */, bits);
            if (outOffset < 0) {
                return -1;
            }
        }

        isFirstCommand[0] = 0;

        outOffset = writeLengthValue(out, base, outOffset, maxOutDataSize, numLiterals, 0, bits);
        if (outOffset < 0) {
            return -1;
        }

        if ((outOffset + numLiterals) > maxOutDataSize) {
            return -1;
        }
        stats.nonzeroLiteralRuns++;
        System.arraycopy(inWindow, literalOffset, out.data, base + outOffset, numLiterals);
        outOffset += numLiterals;

        return outOffset;
    }

    /* ------------------------------------------------------------------ */
    /* Block emission                                                      */
    /* ------------------------------------------------------------------ */

    int writeBlock(Compressor compressor, byte[] inWindow, int startOffset, int endOffset,
            Out out, int base, int maxOutDataSize, BitState bits, int[] finalLiterals,
            int[] curRepMatchOffset, int blockFlags) {
        int repMatchOffset = curRepMatchOffset[0];
        int outOffset = 0;
        int maxOffset = compressor.maxOffset;
        int numLiterals = 0;
        int inFirstLiteralOffset = 0;
        int[] isFirstCommand = new int[1];
        isFirstCommand[0] = blockFlags & 1;
        int i;

        for (i = startOffset; i < endOffset;) {
            final int matchLen = compressor.bestLen[i];
            final int matchOffset = compressor.bestOff[i];

            if (matchLen >= 2 || (matchLen == 1 && matchOffset == repMatchOffset && numLiterals != 0)) {
                if (matchOffset < KcFormat.MIN_OFFSET || matchOffset > maxOffset
                        || matchOffset > KcFormat.MAX_OFFSET) {
                    return -1;
                }

                if (isFirstCommand[0] != 0 && numLiterals == 0) {
                    /* The first block always starts with a literal */
                    return -1;
                }

                if (numLiterals != 0) {
                    outOffset = writeLiterals(compressor, inWindow, inFirstLiteralOffset, numLiterals,
                        out, base, maxOutDataSize, outOffset, bits, isFirstCommand);
                    if (outOffset < 0) {
                        return -1;
                    }
                }

                if (matchOffset == repMatchOffset && numLiterals != 0) {
                    /* Rep match */
                    outOffset = writeBit(out, base, outOffset, maxOutDataSize, 0 /* rep match */, bits);
                    if (outOffset < 0) {
                        return -1;
                    }
                    /* Write match length */
                    outOffset = writeLengthValue(out, base, outOffset, maxOutDataSize, matchLen, 0, bits);
                    if (outOffset < 0) {
                        return -1;
                    }
                } else {
                    /* Match with offset */
                    outOffset = writeBit(out, base, outOffset, maxOutDataSize, 1 /* match with offset */, bits);
                    if (outOffset < 0) {
                        return -1;
                    }

                    final int offsetHigh = (matchOffset - 1) >> 7;
                    if ((compressor.flags & FLG_IS_DALI) != 0) {
                        outOffset = writeLengthValue(out, base, outOffset, maxOutDataSize,
                            offsetHigh + 1, 0, bits);
                        if (outOffset < 0) {
                            return -1;
                        }
                    } else {
                        /* The common 1..128 range needs only the selector bit. Longer
                         * offsets carry their 7-bit-group high part as one raw byte. */
                        outOffset = writeBit(out, base, outOffset, maxOutDataSize,
                            offsetHigh == 0 ? 1 : 0, bits);
                        if (outOffset < 0) {
                            return -1;
                        }

                        if (offsetHigh != 0) {
                            if (outOffset >= maxOutDataSize || offsetHigh >= 0xff) {
                                return -1;
                            }
                            out.data[base + outOffset++] = (byte) offsetHigh;
                        }
                    }

                    /* Write low byte of match offset */
                    if (outOffset >= maxOutDataSize) {
                        return -1;
                    }
                    out.data[base + outOffset++] = (byte) (((matchOffset - 1) & 0x7f) << 1);

                    /* Write match length */
                    if (matchLen == 2) {
                        out.data[base + outOffset - 1] |= 1;
                    } else if (matchLen > 2) {
                        outOffset = writeLengthValue(out, base, outOffset, maxOutDataSize,
                            matchLen - 1, 1, bits);
                        if (outOffset < 0) {
                            return -1;
                        }
                    }
                }

                numLiterals = 0;

                if (matchOffset == repMatchOffset) {
                    compressor.stats.numRepMatches++;
                } else {
                    compressor.stats.numNormalMatches++;
                }

                repMatchOffset = matchOffset;

                if (matchOffset < compressor.stats.minOffset || compressor.stats.minOffset == -1) {
                    compressor.stats.minOffset = matchOffset;
                }
                if (matchOffset > compressor.stats.maxOffset) {
                    compressor.stats.maxOffset = matchOffset;
                }
                compressor.stats.totalOffsets += matchOffset;

                if (matchLen < compressor.stats.minMatchLen || compressor.stats.minMatchLen == -1) {
                    compressor.stats.minMatchLen = matchLen;
                }
                if (matchLen > compressor.stats.maxMatchLen) {
                    compressor.stats.maxMatchLen = matchLen;
                }
                compressor.stats.totalMatchLens += matchLen;
                compressor.stats.matchDivisor++;

                if (matchOffset == 1) {
                    if (matchLen < compressor.stats.minRle1Len || compressor.stats.minRle1Len == -1) {
                        compressor.stats.minRle1Len = matchLen;
                    }
                    if (matchLen > compressor.stats.maxRle1Len) {
                        compressor.stats.maxRle1Len = matchLen;
                    }
                    compressor.stats.totalRle1Lens += matchLen;
                    compressor.stats.rle1Divisor++;
                } else if (matchOffset == 2) {
                    if (matchLen < compressor.stats.minRle2Len || compressor.stats.minRle2Len == -1) {
                        compressor.stats.minRle2Len = matchLen;
                    }
                    if (matchLen > compressor.stats.maxRle2Len) {
                        compressor.stats.maxRle2Len = matchLen;
                    }
                    compressor.stats.totalRle2Lens += matchLen;
                    compressor.stats.rle2Divisor++;
                }

                i += matchLen;

                final int curSafeDist = (i - startOffset) - outOffset;
                if (curSafeDist >= 0 && compressor.stats.safeDist < curSafeDist) {
                    compressor.stats.safeDist = curSafeDist;
                }

                compressor.stats.commandsDivisor++;
            } else {
                if (numLiterals == 0) {
                    inFirstLiteralOffset = i;
                }
                numLiterals++;
                i++;
            }
        }

        if ((blockFlags & 2) != 0) {
            finalLiterals[0] = 0;

            if (numLiterals != 0) {
                /* Final literals */
                outOffset = writeLiterals(compressor, inWindow, inFirstLiteralOffset, numLiterals,
                    out, base, maxOutDataSize, outOffset, bits, isFirstCommand);
                if (outOffset < 0) {
                    return -1;
                }
            }

            outOffset = writeBit(out, base, outOffset, maxOutDataSize, 1 /* match with offset */, bits);
            if (outOffset < 0) {
                return -1;
            }

            if ((compressor.flags & FLG_IS_DALI) != 0) {
                outOffset = writeLengthValue(out, base, outOffset, maxOutDataSize, 256 /* EOD */, 0, bits);
                if (outOffset < 0) {
                    return -1;
                }
            } else {
                outOffset = writeBit(out, base, outOffset, maxOutDataSize,
                    0 /* long-offset selector */, bits);
                if (outOffset < 0) {
                    return -1;
                }
                if (outOffset >= maxOutDataSize) {
                    return -1;
                }
                out.data[base + outOffset++] = 0x00; /* invalid long-offset high: EOD */
            }

            compressor.stats.numEod++;
        } else {
            finalLiterals[0] = numLiterals;
        }

        curRepMatchOffset[0] = repMatchOffset;
        return outOffset;
    }

    /* ------------------------------------------------------------------ */
    /* Arrival helpers                                                     */
    /* ------------------------------------------------------------------ */

    /** memmove(&slots[n+1], &slots[n], (z-n)) for every arrival field. */
    private void shiftArrivalsUp(Compressor c, int base, int n, int z) {
        for (int idx = z - 1; idx >= n; idx--) {
            int from = base + idx;
            int to = from + 1;
            c.arrCost[to] = c.arrCost[from];
            c.arrFromPos[to] = c.arrFromPos[from];
            c.arrFromSlot[to] = c.arrFromSlot[from];
            c.arrRepOffset[to] = c.arrRepOffset[from];
            c.arrRepPos[to] = c.arrRepPos[from];
            c.arrMatchLen[to] = c.arrMatchLen[from];
            c.arrNumLiterals[to] = c.arrNumLiterals[from];
            c.arrScore[to] = c.arrScore[from];
        }
    }

    boolean matches8(byte[] w, int a, int b) {
        for (int k = 0; k < 8; k++) {
            if (w[a + k] != w[b + k]) {
                return false;
            }
        }
        return true;
    }

    boolean matches4(byte[] w, int a, int b) {
        for (int k = 0; k < 4; k++) {
            if (w[a + k] != w[b + k]) {
                return false;
            }
        }
        return true;
    }

    /** memcmp(a, b, n) == 0 */
    boolean matchesN(byte[] w, int a, int b, int n) {
        for (int k = 0; k < n; k++) {
            if (w[a + k] != w[b + k]) {
                return false;
            }
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Forward rep candidate insertion                                     */
    /* ------------------------------------------------------------------ */

    void insertForwardMatch(Compressor compressor, byte[] inWindow, int i, int matchOffset,
            int startOffset, int endOffset, int depth) {
        final int[] arrFromSlot = compressor.arrFromSlot;
        final int[] arrRepOffset = compressor.arrRepOffset;
        final int[] arrRepPos = compressor.arrRepPos;
        final int[] arrNumLiterals = compressor.arrNumLiterals;

        final int maxArrivals = compressor.maxArrivalsPerPosition;
        final int arrivalBase = (i - startOffset) * maxArrivals;
        final int matchesPerPosition = compressor.search.maxMatchesPerPosition;
        final int[] rleLen = compressor.rleLen;
        final int[] visited = compressor.visited;

        for (int j = 0; j < compressor.search.forwardArrivals
                && arrFromSlot[arrivalBase + j] != 0; j++) {
            if (arrNumLiterals[arrivalBase + j] == 0) {
                continue;
            }
            final int nRepOffset = arrRepOffset[arrivalBase + j];
            if (matchOffset == nRepOffset) {
                continue;
            }
            final int nRepPos = arrRepPos[arrivalBase + j];

            if (nRepPos < startOffset || nRepPos >= endOffset || visited[nRepPos] == matchOffset) {
                continue;
            }

            visited[nRepPos] = matchOffset;

            final int fwdBase = (nRepPos - startOffset) * matchesPerPosition;

            if (compressor.matchLen[fwdBase + matchesPerPosition - 1] != 0) {
                continue;
            }
            if (nRepPos < matchOffset) {
                continue;
            }

            final int windowStart = nRepPos;
            if ((inWindow[windowStart] & 0xff) != (inWindow[windowStart - matchOffset] & 0xff)) {
                continue;
            }
            if (nRepOffset == 0) {
                continue;
            }

            int maxRepLen = endOffset - nRepPos;
            if (maxRepLen > LCP_MAX) {
                maxRepLen = LCP_MAX;
            }
            final int windowMax = windowStart + maxRepLen;

            final int len0 = rleLen[nRepPos - matchOffset];
            final int len1 = rleLen[nRepPos];
            int minLen = (len0 < len1) ? len0 : len1;
            int r;

            for (r = 0; compressor.matchLen[fwdBase + r] != 0; r++) {
                if (compressor.matchOff[fwdBase + r] == matchOffset) {
                    if (minLen < compressor.matchLen[fwdBase + r]) {
                        minLen = compressor.matchLen[fwdBase + r];
                    }
                    break;
                }
            }

            int atRepOffset = windowStart + minLen;
            if (atRepOffset > windowMax) {
                atRepOffset = windowMax;
            }

            while ((atRepOffset + 8) < windowMax
                    && matches8(inWindow, atRepOffset, atRepOffset - matchOffset)) {
                atRepOffset += 8;
            }
            while ((atRepOffset + 4) < windowMax
                    && matches4(inWindow, atRepOffset, atRepOffset - matchOffset)) {
                atRepOffset += 4;
            }
            while (atRepOffset < windowMax
                    && (inWindow[atRepOffset] & 0xff) == (inWindow[atRepOffset - matchOffset] & 0xff)) {
                atRepOffset++;
            }

            final int curRepLen = limitMatchLenLocal(atRepOffset - windowStart);

            if (compressor.matchLen[fwdBase + r] == 0) {
                compressor.matchLen[fwdBase + r] = curRepLen;
                compressor.matchOff[fwdBase + r] = matchOffset;
                compressor.matchDepth[fwdBase + r] = 0;
                if (compressor.collectDiagnostics) {
                    compressor.diagnostics.forwardRepeatCandidates++;
                    if ((r + 1) > compressor.diagnostics.maxMatchesReached) {
                        compressor.diagnostics.maxMatchesReached = r + 1;
                    }
                    if ((r + 1) == matchesPerPosition) {
                        compressor.diagnostics.matchPositionsAtLimit++;
                    }
                }

                if (depth < compressor.search.forwardRepeatDepth) {
                    insertForwardMatch(compressor, inWindow, nRepPos, matchOffset,
                        startOffset, endOffset, depth + 1);
                }
            } else if (compressor.matchLen[fwdBase + r] < curRepLen
                    && compressor.matchDepth[fwdBase + r] == 0) {
                compressor.matchLen[fwdBase + r] = curRepLen;
            }
        }
    }

    /* Optimal parse (arrival DP), port of salvador_optimize_forward. */
    void optimizeForward(Compressor compressor, byte[] inWindow, int startOffset, int endOffset,
            int insertForwardReps, int[] curRepMatchOffset, int arrivalsPerPosition, int blockFlags) {
        final int maxArrivals = compressor.maxArrivalsPerPosition;
        final int[] cost = compressor.arrCost;
        final int[] fromPos = compressor.arrFromPos;
        final int[] fromSlot = compressor.arrFromSlot;
        final int[] repOffsetArr = compressor.arrRepOffset;
        final int[] repPosArr = compressor.arrRepPos;
        final int[] matchLenArr = compressor.arrMatchLen;
        final int[] numLitArr = compressor.arrNumLiterals;
        final int[] scoreArr = compressor.arrScore;
        final int[] rleLen = compressor.rleLen;
        final boolean diag = compressor.collectDiagnostics;
        final KcSearch.Diagnostics diagnostics = compressor.diagnostics;
        int i;

        if ((endOffset - startOffset) > compressor.blockSize) {
            return;
        }

        for (i = startOffset * maxArrivals; i != (endOffset + 1) * maxArrivals; i += maxArrivals) {
            for (int j = 0; j < maxArrivals; j++) {
                int slot = i + j;
                cost[slot] = 0x40000000;
                fromPos[slot] = 0;
                fromSlot[slot] = 0;
                repOffsetArr[slot] = 0;
                repPosArr[slot] = 0;
                matchLenArr[slot] = 0;
                numLitArr[slot] = 0;
                scoreArr[slot] = 0;
            }
        }

        final int startSlot = startOffset * maxArrivals;
        cost[startSlot] = 0;
        fromSlot[startSlot] = -1;
        repOffsetArr[startSlot] = curRepMatchOffset[0];

        if (insertForwardReps != 0) {
            final int[] visited = compressor.visited;
            for (int v = startOffset; v < endOffset; v++) {
                visited[v] = 0;
            }
        }

        for (i = startOffset; i != endOffset; i++) {
            final int curBase = i * maxArrivals;
            final int litBase = (i + 1) * maxArrivals;
            int j;

            for (j = 0; j < arrivalsPerPosition && fromSlot[curBase + j] != 0; j++) {
                if (diag) {
                    diagnostics.arrivalCandidatesConsidered++;
                }
                final int numLiterals = numLitArr[curBase + j] + 1;
                final int previousLiteralCost = getLiteralsVarlenSize(numLiterals - 1);
                final int codingChoiceCost = cost[curBase + j] + 8 /* literal */
                    + getLiteralsVarlenSize(numLiterals) - previousLiteralCost;
                final int score = scoreArr[curBase + j] + 1;
                final int repOffset = repOffsetArr[curBase + j];

                final int lastSlot = litBase + arrivalsPerPosition - 1;
                if (codingChoiceCost < cost[lastSlot]
                        || (codingChoiceCost == cost[lastSlot] && score < scoreArr[lastSlot]
                            && repOffset != repOffsetArr[lastSlot])) {
                    int exists = 0;
                    int n;

                    for (n = 0; cost[litBase + n] < codingChoiceCost; n++) {
                        if (repOffsetArr[litBase + n] == repOffset) {
                            exists = 1;
                            break;
                        }
                    }

                    if (exists == 0) {
                        for (; cost[litBase + n] == codingChoiceCost
                                && score >= scoreArr[litBase + n]; n++) {
                            if (repOffsetArr[litBase + n] == repOffset) {
                                exists = 1;
                                break;
                            }
                        }

                        if (exists == 0) {
                            int z;
                            for (z = n; z < arrivalsPerPosition - 1
                                    && cost[litBase + z] == codingChoiceCost; z++) {
                                if (repOffsetArr[litBase + z] == repOffset) {
                                    exists = 1;
                                    break;
                                }
                            }

                            if (exists == 0) {
                                for (; z < arrivalsPerPosition - 1 && fromSlot[litBase + z] != 0; z++) {
                                    if (repOffsetArr[litBase + z] == repOffset) {
                                        break;
                                    }
                                }

                                shiftArrivalsUp(compressor, litBase, n, z);

                                final int slot = litBase + n;
                                cost[slot] = codingChoiceCost;
                                fromPos[slot] = i;
                                fromSlot[slot] = j + 1;
                                repOffsetArr[slot] = repOffset;
                                repPosArr[slot] = repPosArr[curBase + j];
                                matchLenArr[slot] = 0;
                                numLitArr[slot] = numLiterals;
                                scoreArr[slot] = score;
                                if (diag) {
                                    diagnostics.arrivalsRetained++;
                                }
                            }
                        }
                    }
                    if (exists != 0 && diag) {
                        diagnostics.arrivalsDiscardedDuplicate++;
                    }
                } else if (diag) {
                    diagnostics.arrivalsDiscardedLimit++;
                }
            }

            if (i == startOffset && (blockFlags & 1) != 0) {
                continue;
            }

            final int numArrivalsForThisPos = j;
            if (diag && numArrivalsForThisPos > diagnostics.maxArrivalsReached) {
                diagnostics.maxArrivalsReached = numArrivalsForThisPos;
            }
            int overallMinRepLen = 0;
            int overallMaxRepLen = 0;

            final int[] repMatchArrivalIdx = compressor.repMatchArrivalIdx;
            int numRepMatchArrivals = 0;

            if (i < endOffset) {
                int maxRepLenForPos = endOffset - i;
                if (maxRepLenForPos > LCP_MAX) {
                    maxRepLenForPos = LCP_MAX;
                }
                final int windowStart = i;
                final int windowMax = windowStart + maxRepLenForPos;

                for (j = 0; j < numArrivalsForThisPos; j++) {
                    if (numLitArr[curBase + j] == 0) {
                        continue;
                    }
                    final int nRepOffset = repOffsetArr[curBase + j];
                    if (i < nRepOffset) {
                        continue;
                    }
                    if ((inWindow[windowStart] & 0xff) != (inWindow[windowStart - nRepOffset] & 0xff)) {
                        continue;
                    }
                    if (nRepOffset == 0) {
                        continue;
                    }

                    final int nLen0 = rleLen[i - nRepOffset];
                    final int nLen1 = rleLen[i];
                    final int nMinLen = (nLen0 < nLen1) ? nLen0 : nLen1;
                    int atPos = windowStart + nMinLen;
                    if (atPos > windowMax) {
                        atPos = windowMax;
                    }

                    while ((atPos + 8) < windowMax && matches8(inWindow, atPos, atPos - nRepOffset)) {
                        atPos += 8;
                    }
                    while ((atPos + 4) < windowMax && matches4(inWindow, atPos, atPos - nRepOffset)) {
                        atPos += 4;
                    }
                    while (atPos < windowMax
                            && (inWindow[atPos] & 0xff) == (inWindow[atPos - nRepOffset] & 0xff)) {
                        atPos++;
                    }
                    final int curRepLen = atPos - windowStart;

                    if (overallMaxRepLen < curRepLen) {
                        overallMaxRepLen = curRepLen;
                    }
                    repMatchArrivalIdx[numRepMatchArrivals++] = j;
                    repMatchArrivalIdx[numRepMatchArrivals++] = curRepLen;
                }
            }
            repMatchArrivalIdx[numRepMatchArrivals] = -1;

            final int matchesPerPosition = compressor.search.maxMatchesPerPosition;
            final int matchBase = (i - startOffset) * matchesPerPosition;

            for (int m = 0; m < matchesPerPosition && compressor.matchLen[matchBase + m] != 0; m++) {
                int origMatchLen = compressor.matchLen[matchBase + m];
                final int origMatchOffset = compressor.matchOff[matchBase + m];
                final int origMatchDepth = compressor.matchDepth[matchBase + m];

                if ((i + origMatchLen) > endOffset) {
                    origMatchLen = endOffset - i;
                }

                for (int d = 0; d <= origMatchDepth; d += (origMatchDepth != 0 ? origMatchDepth : 1)) {
                    final int matchLen = origMatchLen - d;
                    final int matchOffset = origMatchOffset - d;
                    int nonRepMatchArrivalIdx;
                    int startingMatchLen;
                    int k;

                    if (insertForwardReps != 0) {
                        insertForwardMatch(compressor, inWindow, i, matchOffset, startOffset, endOffset, 0);
                    }

                    nonRepMatchArrivalIdx = -1;
                    for (j = 0; j < numArrivalsForThisPos; j++) {
                        if (matchOffset != repOffsetArr[curBase + j] || numLitArr[curBase + j] == 0) {
                            nonRepMatchArrivalIdx = j;
                            break;
                        }
                    }

                    if (nonRepMatchArrivalIdx >= 0) {
                        final int noRepmatchOffsetCost = cost[curBase + nonRepMatchArrivalIdx]
                            + offsetBitCost(compressor, matchOffset);
                        final int noRepmatchScore = scoreArr[curBase + nonRepMatchArrivalIdx]
                            + (matchOffset == 1 ? 1 : 3);

                        if (matchLen < compressor.search.exhaustiveLengthThreshold) {
                            startingMatchLen = 2;
                        } else {
                            startingMatchLen = matchLen;
                        }
                        if (diag) {
                            diagnostics.endpointsEvaluated += (matchLen - startingMatchLen + 1);
                            if (startingMatchLen > 2) {
                                diagnostics.endpointsPruned += (startingMatchLen - 2);
                            }
                        }

                        for (k = startingMatchLen; k <= matchLen; k++) {
                            final int destBase = (i + k) * maxArrivals;
                            final int matchLenCost = tokenCost + getMatchVarlenSizeNoRep(k);
                            final int codingChoiceCost = matchLenCost + noRepmatchOffsetCost;
                            if (diag) {
                                diagnostics.arrivalCandidatesConsidered++;
                            }

                            final int gateSlot = destBase + arrivalsPerPosition - 2;
                            if (codingChoiceCost < cost[gateSlot]
                                    || (codingChoiceCost == cost[gateSlot]
                                        && noRepmatchScore < scoreArr[gateSlot]
                                        && (codingChoiceCost != cost[destBase + arrivalsPerPosition - 1]
                                            || matchOffset != repOffsetArr[destBase + arrivalsPerPosition - 1]))) {
                                int exists = 0;
                                int n;

                                for (n = 0; cost[destBase + n] < codingChoiceCost; n++) {
                                    if (repOffsetArr[destBase + n] == matchOffset) {
                                        exists = 1;
                                        break;
                                    }
                                }

                                if (exists == 0) {
                                    for (; cost[destBase + n] == codingChoiceCost
                                            && noRepmatchScore >= scoreArr[destBase + n]; n++) {
                                        if (repOffsetArr[destBase + n] == matchOffset) {
                                            exists = 1;
                                            break;
                                        }
                                    }

                                    if (exists == 0) {
                                        int z;
                                        for (z = n; z < arrivalsPerPosition - 1
                                                && cost[destBase + z] == codingChoiceCost; z++) {
                                            if (repOffsetArr[destBase + z] == matchOffset) {
                                                exists = 1;
                                                break;
                                            }
                                        }

                                        if (exists == 0) {
                                            for (; z < arrivalsPerPosition - 1
                                                    && fromSlot[destBase + z] != 0; z++) {
                                                if (repOffsetArr[destBase + z] == matchOffset) {
                                                    break;
                                                }
                                            }

                                            shiftArrivalsUp(compressor, destBase, n, z);

                                            final int slot = destBase + n;
                                            cost[slot] = codingChoiceCost;
                                            fromPos[slot] = i;
                                            fromSlot[slot] = nonRepMatchArrivalIdx + 1;
                                            repOffsetArr[slot] = matchOffset;
                                            repPosArr[slot] = i;
                                            matchLenArr[slot] = k;
                                            numLitArr[slot] = 0;
                                            scoreArr[slot] = noRepmatchScore;
                                            if (diag) {
                                                diagnostics.arrivalsRetained++;
                                            }
                                        }
                                    }
                                }
                                if (exists != 0 && diag) {
                                    diagnostics.arrivalsDiscardedDuplicate++;
                                }
                            } else if (diag) {
                                diagnostics.arrivalsDiscardedLimit++;
                            }
                        }
                    }
                    /* Insert repmatch candidates */
                    if (matchLen < compressor.search.exhaustiveLengthThreshold
                            || matchLen <= overallMinRepLen) {
                        startingMatchLen = overallMinRepLen + 1;
                    } else {
                        startingMatchLen = matchLen;
                    }

                    final int repMax = (overallMaxRepLen < matchLen) ? overallMaxRepLen : matchLen;
                    for (k = startingMatchLen; k <= repMax; k++) {
                        if (diag) {
                            diagnostics.endpointsEvaluated++;
                        }
                        final int matchLenCost = tokenCost + getMatchVarlenSizeRep(k);
                        final int destBase = (i + k) * maxArrivals;
                        int repArrival = 0;

                        while ((j = repMatchArrivalIdx[repArrival]) >= 0) {
                            if (repMatchArrivalIdx[repArrival + 1] < k) {
                                repArrival += 2;
                                continue;
                            }
                            final int repCodingChoiceCost = cost[curBase + j] + matchLenCost;
                            final int repOffset = repOffsetArr[curBase + j];
                            final int score = scoreArr[curBase + j] + (repOffset == 1 ? 0 : 2);
                            if (diag) {
                                diagnostics.arrivalCandidatesConsidered++;
                            }

                            final int gateSlot = destBase + arrivalsPerPosition - 1;
                            if (repCodingChoiceCost < cost[gateSlot]
                                    || (repCodingChoiceCost == cost[gateSlot]
                                        && score < scoreArr[gateSlot]
                                        && repOffset != repOffsetArr[gateSlot])) {
                                int exists = 0;
                                int n;

                                for (n = 0; cost[destBase + n] < repCodingChoiceCost; n++) {
                                    if (repOffsetArr[destBase + n] == repOffset) {
                                        exists = 1;
                                        break;
                                    }
                                }

                                if (exists == 0) {
                                    for (; cost[destBase + n] == repCodingChoiceCost
                                            && score >= scoreArr[destBase + n]; n++) {
                                        if (repOffsetArr[destBase + n] == repOffset) {
                                            exists = 1;
                                            break;
                                        }
                                    }

                                    if (exists == 0) {
                                        int z;
                                        for (z = n; z < arrivalsPerPosition - 1
                                                && cost[destBase + z] == repCodingChoiceCost; z++) {
                                            if (repOffsetArr[destBase + z] == repOffset) {
                                                exists = 1;
                                                break;
                                            }
                                        }

                                        if (exists == 0) {
                                            for (; z < arrivalsPerPosition - 1
                                                    && fromSlot[destBase + z] != 0; z++) {
                                                if (repOffsetArr[destBase + z] == repOffset) {
                                                    break;
                                                }
                                            }

                                            shiftArrivalsUp(compressor, destBase, n, z);

                                            final int slot = destBase + n;
                                            cost[slot] = repCodingChoiceCost;
                                            fromPos[slot] = i;
                                            fromSlot[slot] = j + 1;
                                            repOffsetArr[slot] = repOffset;
                                            repPosArr[slot] = i;
                                            matchLenArr[slot] = k;
                                            numLitArr[slot] = 0;
                                            scoreArr[slot] = score;
                                            if (diag) {
                                                diagnostics.arrivalsRetained++;
                                            }
                                        }
                                    }
                                }
                                if (exists != 0 && diag) {
                                    diagnostics.arrivalsDiscardedDuplicate++;
                                }
                            } else {
                                if (diag) {
                                    diagnostics.arrivalsDiscardedLimit++;
                                }
                                break;
                            }
                            repArrival += 2;
                        }

                        if (k <= compressor.search.exhaustiveLengthThreshold) {
                            overallMinRepLen = k;
                        } else if (overallMaxRepLen == k) {
                            overallMaxRepLen--;
                        }
                    }
                }

                if (compressor.search.enableLongMatchEarlyBreak != 0
                        && origMatchLen >= compressor.search.longMatchBreakThreshold
                        && ((m + 1) >= matchesPerPosition
                            || compressor.matchLen[matchBase + m + 1] < compressor.search.remainingMatchThreshold)) {
                    if (diag) {
                        diagnostics.longMatchEarlyBreaks++;
                    }
                    break;
                }
            }
        }

        if (insertForwardReps == 0) {
            int endSlot = i * maxArrivals;
            final int[] bestLen = compressor.bestLen;
            final int[] bestOff = compressor.bestOff;

            while (fromSlot[endSlot] > 0 && fromPos[endSlot] < endOffset) {
                final int pos = fromPos[endSlot];
                bestLen[pos] = limitMatchLenLocal(matchLenArr[endSlot]);
                bestOff[pos] = matchLenArr[endSlot] != 0 ? repOffsetArr[endSlot] : 0;

                endSlot = fromPos[endSlot] * maxArrivals + (fromSlot[endSlot] - 1);
            }
        }
    }

    /* Port of salvador_reduce_commands: merge/replace matches by literals. */
    int reduceCommands(Compressor c, byte[] w, int startOffset, int endOffset,
            int[] curRepMatchOffset, int blockFlags) {
        final int[] bl = c.bestLen;
        final int[] bo = c.bestOff;
        int numLiterals = blockFlags & 1;
        int repMatchOffset = curRepMatchOffset[0];
        int didReduce = 0;
        int i;

        for (i = startOffset + (blockFlags & 1); i < endOffset;) {
            final int mLen = bl[i];
            final int mOff = bo[i];

            if (mLen == 0 && (i + 1) < endOffset && bl[i + 1] >= MIN_ENCODED_MATCH_SIZE
                    && bl[i + 1] < KcFormat.MAX_VARLEN && bo[i + 1] != 0 && i >= bo[i + 1]
                    && (i + bl[i + 1] + 1) <= endOffset
                    && (numLiterals != 0 || bo[i + 1] != repMatchOffset)
                    && matchesN(w, i - bo[i + 1], i, bl[i + 1] + 1)) {
                final int nextLen = bl[i + 1];
                final int nextOff = bo[i + 1];
                int curLenSize;
                int reducedLenSize;

                if (nextOff == repMatchOffset) {
                    curLenSize = getLiteralsVarlenSize(numLiterals + 1) + 8
                        + getMatchVarlenSizeRep(nextLen);
                } else {
                    curLenSize = getLiteralsVarlenSize(numLiterals + 1) + 8
                        + offsetBitCost(c, nextOff) + getMatchVarlenSizeNoRep(nextLen);
                }

                if (numLiterals != 0 && nextOff == repMatchOffset && repMatchOffset != 0) {
                    reducedLenSize = getLiteralsVarlenSize(numLiterals)
                        + getMatchVarlenSizeRep(nextLen + 1);
                } else {
                    reducedLenSize = getLiteralsVarlenSize(numLiterals)
                        + offsetBitCost(c, nextOff) + getMatchVarlenSizeNoRep(nextLen + 1);
                }

                if (reducedLenSize <= curLenSize) {
                    /* Merge */
                    bl[i] = limitMatchLenLocal(nextLen + 1);
                    bo[i] = nextOff;
                    bl[i + 1] = 0;
                    bo[i + 1] = 0;
                    didReduce = 1;
                    continue;
                }
            }

            if (mLen >= MIN_ENCODED_MATCH_SIZE) {
                if ((i + mLen) < endOffset) {
                    int nextIndex = i + mLen;
                    int nextLiterals = 0;

                    while (nextIndex < endOffset && bl[nextIndex] == 0) {
                        nextLiterals++;
                        nextIndex++;
                    }

                    if (nextIndex < endOffset) {
                        if (bl[nextIndex] >= MIN_ENCODED_MATCH_SIZE) {
                            if (numLiterals != 0 && repMatchOffset != 0 && mOff != repMatchOffset
                                    && (bo[nextIndex] != mOff
                                        || offsetBitCost(c, mOff) > offsetBitCost(c, bo[nextIndex]))) {
                                if (i >= repMatchOffset && (i - repMatchOffset + mLen) <= endOffset) {
                                    int maxLen = 0;
                                    while ((maxLen + 8) < mLen && matches8(w, i - repMatchOffset + maxLen,
                                            i - mOff + maxLen)) {
                                        maxLen += 8;
                                    }
                                    while ((maxLen + 4) < mLen && matches4(w, i - repMatchOffset + maxLen,
                                            i - mOff + maxLen)) {
                                        maxLen += 4;
                                    }
                                    while (maxLen < mLen && w[i - repMatchOffset + maxLen] == w[i - mOff + maxLen]) {
                                        maxLen++;
                                    }

                                    if (maxLen >= 1) {
                                        int curCommandSize = offsetBitCost(c, mOff)
                                            + getMatchVarlenSizeNoRep(mLen)
                                            + getLiteralsVarlenSize(nextLiterals);
                                        int reducedCommandSize = getMatchVarlenSizeRep(maxLen)
                                            + ((mLen - maxLen) << 3)
                                            + getLiteralsVarlenSize(nextLiterals + (mLen - maxLen));

                                        if (reducedCommandSize < curCommandSize) {
                                            bo[i] = repMatchOffset;
                                            for (int j = maxLen; j < mLen; j++) {
                                                bl[i + j] = 0;
                                            }
                                            bl[i] = limitMatchLenLocal(maxLen);
                                            didReduce = 1;
                                        }
                                    }
                                }
                            }
                            if (bo[nextIndex] != 0 && bo[i] != bo[nextIndex]
                                    && repMatchOffset != bo[nextIndex] && nextLiterals != 0) {
                                if (i >= bo[nextIndex]
                                        && (i - bo[nextIndex] + bl[i]) <= endOffset
                                        && bo[i] != repMatchOffset) {
                                    int maxLen = 0;
                                    while ((maxLen + 8) < bl[i] && matches8(w,
                                            i - bo[nextIndex] + maxLen, i - bo[i] + maxLen)) {
                                        maxLen += 8;
                                    }
                                    while ((maxLen + 4) < bl[i] && matches4(w,
                                            i - bo[nextIndex] + maxLen, i - bo[i] + maxLen)) {
                                        maxLen += 4;
                                    }
                                    while (maxLen < bl[i]
                                            && w[i - bo[nextIndex] + maxLen] == w[i - bo[i] + maxLen]) {
                                        maxLen++;
                                    }

                                    if (maxLen >= bl[i]) {
                                        /* Replace */
                                        bo[i] = bo[nextIndex];
                                        didReduce = 1;
                                    } else if (maxLen >= 2) {
                                        int partialBefore = getMatchVarlenSizeNoRep(bl[i])
                                            + offsetBitCost(c, bo[i])
                                            + getLiteralsVarlenSize(nextLiterals);
                                        int partialAfter = getMatchVarlenSizeRep(maxLen)
                                            + getLiteralsVarlenSize(nextLiterals + (bl[i] - maxLen))
                                            + ((bl[i] - maxLen) << 3);

                                        if (partialAfter < partialBefore) {
                                            bo[i] = bo[nextIndex];
                                            for (int j = maxLen; j < bl[i]; j++) {
                                                bl[i + j] = 0;
                                            }
                                            bl[i] = limitMatchLenLocal(maxLen);
                                            didReduce = 1;
                                        }
                                    }
                                }
                            }
                        }
                        if (bl[i] < 9) {
                            int curCommandSize = getLiteralsVarlenSize(numLiterals);
                            if (bo[i] == repMatchOffset && numLiterals != 0 && repMatchOffset != 0) {
                                curCommandSize += getMatchVarlenSizeRep(bl[i]);
                            } else {
                                curCommandSize += offsetBitCost(c, bo[i]);
                                curCommandSize += getMatchVarlenSizeNoRep(bl[i]);
                            }

                            int nextCommandSize = getLiteralsVarlenSize(nextLiterals);
                            nextCommandSize += 1;
                            if (bo[i] != 0 && bo[nextIndex] == bo[i] && nextLiterals != 0) {
                                nextCommandSize += getMatchVarlenSizeRep(bl[nextIndex]);
                            } else {
                                nextCommandSize += offsetBitCost(c, bo[nextIndex]);
                                nextCommandSize += getMatchVarlenSizeNoRep(bl[nextIndex]);
                            }

                            final int originalCombined = curCommandSize + nextCommandSize;
                            int reduced = (bl[i] << 3);
                            reduced += getLiteralsVarlenSize(numLiterals + bl[i] + nextLiterals);
                            if (bo[nextIndex] == repMatchOffset
                                    && (numLiterals + bl[i] + nextLiterals) != 0 && repMatchOffset != 0) {
                                reduced += getMatchVarlenSizeRep(bl[nextIndex]);
                            } else {
                                reduced += offsetBitCost(c, bo[nextIndex]);
                                reduced += getMatchVarlenSizeNoRep(bl[nextIndex]);
                            }

                            if (originalCombined >= reduced) {
                                /* Reduce */
                                final int reduceLen = bl[i];
                                for (int j = 0; j < reduceLen; j++) {
                                    bl[i + j] = 0;
                                }
                                didReduce = 1;
                                continue;
                            }
                        }
                    }
                }
                if ((i + bl[i]) < endOffset && bo[i] != 0 && bl[i] >= MIN_ENCODED_MATCH_SIZE
                        && bo[i + bl[i]] != 0 && bl[i + bl[i]] >= MIN_ENCODED_MATCH_SIZE
                        && (bl[i] + bl[i + bl[i]]) <= KcFormat.MAX_VARLEN
                        && (i + bl[i]) >= bo[i]
                        && (i + bl[i]) >= bo[i + bl[i]]
                        && (i + bl[i] + bl[i + bl[i]]) <= endOffset
                        && matchesN(w, i - bo[i] + bl[i],
                            i + bl[i] - bo[i + bl[i]], bl[i + bl[i]])) {

                    final int curLen = bl[i];
                    final int curOff = bo[i];
                    final int secondOff = bo[i + curLen];
                    final int secondLen = bl[i + curLen];

                    int nextIndex = i + curLen + secondLen;
                    int nextLiterals = 0;
                    while (nextIndex < endOffset && bl[nextIndex] == 0) {
                        nextIndex++;
                        nextLiterals++;
                    }

                    int curPartialSize;
                    if (curOff == repMatchOffset && numLiterals != 0) {
                        curPartialSize = getMatchVarlenSizeRep(curLen);
                    } else {
                        curPartialSize = offsetBitCost(c, curOff) + getMatchVarlenSizeNoRep(curLen);
                    }
                    curPartialSize += 1; /* match with offset */
                    curPartialSize += offsetBitCost(c, secondOff);
                    curPartialSize += getMatchVarlenSizeNoRep(secondLen);

                    if (nextIndex < endOffset) {
                        if (secondOff != 0 && bo[nextIndex] == secondOff && nextLiterals != 0) {
                            curPartialSize += getMatchVarlenSizeRep(bl[nextIndex]);
                        } else {
                            curPartialSize += offsetBitCost(c, bo[nextIndex]);
                            curPartialSize += getMatchVarlenSizeNoRep(bl[nextIndex]);
                        }
                    }

                    int reducedPartialSize;
                    if (curOff == repMatchOffset && numLiterals != 0 && repMatchOffset != 0) {
                        reducedPartialSize = getMatchVarlenSizeRep(curLen + secondLen);
                    } else {
                        reducedPartialSize = offsetBitCost(c, curOff);
                        reducedPartialSize += getMatchVarlenSizeNoRep(curLen + secondLen);
                    }

                    int cannotReduce = 0;
                    if (nextIndex < endOffset) {
                        if (curOff != 0 && bo[nextIndex] == curOff && nextLiterals != 0) {
                            reducedPartialSize += getMatchVarlenSizeRep(bl[nextIndex]);
                        } else if (bl[nextIndex] >= MIN_ENCODED_MATCH_SIZE) {
                            reducedPartialSize += offsetBitCost(c, bo[nextIndex]);
                            reducedPartialSize += getMatchVarlenSizeNoRep(bl[nextIndex]);
                        } else {
                            cannotReduce = 1;
                        }
                    }

                    if (curPartialSize >= reducedPartialSize && cannotReduce == 0) {
                        /* Join */
                        bl[i] = limitMatchLenLocal(curLen + secondLen);
                        bl[i + curLen] = 0;
                        bo[i + curLen] = 0;
                        didReduce = 1;
                        continue;
                    }
                }
            if (numLiterals != 0 && bo[i] != repMatchOffset && bl[i] == MIN_ENCODED_MATCH_SIZE
                    && repMatchOffset != 0) {
                if ((i + MIN_ENCODED_MATCH_SIZE) < endOffset) {
                    int nextIndex = i + MIN_ENCODED_MATCH_SIZE;
                    int nextLiterals = 0;

                    while (nextIndex < endOffset && bl[nextIndex] == 0) {
                        nextLiterals++;
                        nextIndex++;
                    }

                    if (nextIndex < endOffset && nextLiterals != 0
                            && bl[nextIndex] == 1 && bo[nextIndex] == bo[i]) {
                        int nextNextIndex = nextIndex + 1;
                        int nextNextLiterals = 0;

                        while (nextNextIndex < endOffset && bl[nextNextIndex] == 0) {
                            nextNextLiterals++;
                            nextNextIndex++;
                        }

                        if (nextNextIndex < endOffset && nextNextLiterals != 0
                                && bl[nextNextIndex] >= MIN_ENCODED_MATCH_SIZE
                                && bo[nextNextIndex] != bo[nextIndex]) {
                            int curCommandSize = getLiteralsVarlenSize(numLiterals);
                            curCommandSize += 1;
                            curCommandSize += offsetBitCost(c, bo[i]);
                            curCommandSize += 1;

                            int curRepMatchSize = getLiteralsVarlenSize(nextLiterals);
                            curRepMatchSize += (nextLiterals << 3);
                            curRepMatchSize += 1;
                            curRepMatchSize += 1;

                            int reduced = getLiteralsVarlenSize(
                                numLiterals + MIN_ENCODED_MATCH_SIZE + nextLiterals + 1);
                            reduced += (MIN_ENCODED_MATCH_SIZE << 3);
                            reduced += (nextLiterals << 3);
                            reduced += (1 << 3);

                            if ((curCommandSize + curRepMatchSize) >= reduced) {
                                for (int j = 0; j < MIN_ENCODED_MATCH_SIZE; j++) {
                                    bl[i + j] = 0;
                                }
                                bl[nextIndex] = 0;
                                didReduce = 1;
                            }
                        }
                    }
                }
            }

            repMatchOffset = bo[i];
            i += bl[i];
            numLiterals = 0;
        } else if (bl[i] == 1) {
            if (numLiterals != 0) {
                int nextIndex = i + 1;
                int nextLiterals = 0;

                while (nextIndex < endOffset && bl[nextIndex] == 0) {
                    nextLiterals++;
                    nextIndex++;
                }

                if (repMatchOffset != bo[i] && (nextIndex < endOffset || didReduce == 0)) {
                    bl[i] = 0;
                    bo[i] = 0;
                    didReduce = 1;
                    continue;
                }

                if (nextLiterals != 0) {
                    int curPartialSize = getLiteralsVarlenSize(numLiterals);
                    curPartialSize += tokenCost + 1;
                    curPartialSize += getLiteralsVarlenSize(nextLiterals);

                    final int reducedPartialSize = getLiteralsVarlenSize(
                        numLiterals + 1 + nextLiterals) + 8;

                    if (curPartialSize >= reducedPartialSize) {
                        bl[i] = 0;
                        bo[i] = 0;
                        didReduce = 1;
                        continue;
                    }
                }
            }

            numLiterals = 0;
            i++;
        } else {
            numLiterals++;
            i++;
        }
        }

        return didReduce;
    }

    /* Port of salvador_optimize_and_write_block. */
    int optimizeAndWriteBlock(Compressor c, byte[] w, int prevBlockSize, int inDataSize,
            Out out, int base, int maxOutDataSize, BitState bits, int[] finalLiterals,
            int[] curRepMatchOffset, int blockFlags) {
        final int endOffset = prevBlockSize + inDataSize;
        final int matchesPerPosition = c.search.maxMatchesPerPosition;
        final int[] rleLen = c.rleLen;
        final int[] firstOffsetForByte = c.firstOffsetForByte;
        final int[] nextOffsetForPos = c.nextOffsetForPos;
        final int[] offsetCache = c.offsetCache;
        final int[] bl = c.bestLen;
        final int[] bo = c.bestOff;
        int nPosition;

        for (int k = 0; k < c.blockSize; k++) {
            bl[k] = 0;
            bo[k] = 0;
        }

        /* Count identical bytes */
        int i = 0;
        while (i < endOffset) {
            int rangeStart = i;
            final byte ch = w[rangeStart];
            do {
                i++;
            } while (i < endOffset && w[i] == ch);
            while (rangeStart < i) {
                rleLen[rangeStart] = i - rangeStart;
                rangeStart++;
            }
        }

        /* Supplement small matches */
        for (int k = 0; k < 65536; k++) {
            firstOffsetForByte[k] = -1;
        }
        for (int k = 0; k < inDataSize; k++) {
            nextOffsetForPos[k] = -1;
        }

        for (nPosition = prevBlockSize; nPosition < (endOffset - 1); nPosition++) {
            final int key = (w[nPosition] & 0xff) | ((w[nPosition + 1] & 0xff) << 8);
            nextOffsetForPos[nPosition - prevBlockSize] = firstOffsetForByte[key];
            firstOffsetForByte[key] = nPosition;
        }

        for (int k = 0; k < 2048; k++) {
            offsetCache[k] = -1;
        }

        for (nPosition = prevBlockSize + 1; nPosition < (endOffset - 1); nPosition++) {
            final int matchBase = (nPosition - prevBlockSize) * matchesPerPosition;
            final int supplementLength = c.search.supplementMatchLengthLimit;
            final int maxMatchLen = ((nPosition + supplementLength) < endOffset)
                ? supplementLength : (endOffset - nPosition);
            final int windowMax = nPosition + maxMatchLen;
            final int windowStart = nPosition;
            int m = 0;
            int matchPos;

            while (m < c.search.initialSupplementMatches && c.matchLen[matchBase + m] != 0) {
                offsetCache[c.matchOff[matchBase + m] & 2047] = nPosition;
                offsetCache[(c.matchOff[matchBase + m] - c.matchDepth[matchBase + m]) & 2047] = nPosition;
                m++;
            }

            for (matchPos = nextOffsetForPos[nPosition - prevBlockSize];
                    m < c.search.initialSupplementMatches && matchPos >= 0;
                    matchPos = nextOffsetForPos[matchPos - prevBlockSize]) {
                final int matchOffset = nPosition - matchPos;

                if (matchOffset > c.maxOffset) {
                    break;
                }

                int alreadyExists = 0;
                if (offsetCache[matchOffset & 2047] == nPosition) {
                    for (int existing = 0; existing < m; existing++) {
                        if (c.matchOff[matchBase + existing] == matchOffset
                                || (c.matchOff[matchBase + existing] - c.matchDepth[matchBase + existing]) == matchOffset) {
                            alreadyExists = 1;
                            break;
                        }
                    }
                }

                if (alreadyExists != 0) {
                    continue;
                }

                final int len0 = rleLen[matchPos];
                final int len1 = rleLen[nPosition];
                final int minLen = (len0 < len1) ? len0 : len1;
                int atPos = windowStart + minLen;

                if (atPos > windowMax) {
                    atPos = windowMax;
                }

                while ((atPos + 8) < windowMax && matches8(w, atPos, atPos - matchOffset)) {
                    atPos += 8;
                }
                while ((atPos + 4) < windowMax && matches4(w, atPos, atPos - matchOffset)) {
                    atPos += 4;
                }
                while (atPos < windowMax && w[atPos] == w[atPos - matchOffset]) {
                    atPos++;
                }

                c.matchLen[matchBase + m] = limitMatchLenLocal(atPos - windowStart);
                c.matchOff[matchBase + m] = matchOffset;
                c.matchDepth[matchBase + m] = 0;
                m++;
                if (c.collectDiagnostics && m > c.diagnostics.maxMatchesReached) {
                    c.diagnostics.maxMatchesReached = m;
                }
                if (c.collectDiagnostics && m == matchesPerPosition) {
                    c.diagnostics.matchPositionsAtLimit++;
                }
            }
        }
        /* Compress and insert additional matches */
        optimizeForward(c, w, prevBlockSize, endOffset, 1 /* insertForwardReps */,
            curRepMatchOffset, c.search.forwardArrivals, blockFlags);

        /* Supplement matches further */
        for (nPosition = prevBlockSize + 1; nPosition < (endOffset - 1); nPosition++) {
            final int matchBase = (nPosition - prevBlockSize) * matchesPerPosition;

            if (c.matchLen[matchBase] >= c.search.supplementTriggerLength) {
                continue;
            }

            final int supplementLength = c.search.supplementMatchLengthLimit;
            final int maxMatchLen = ((nPosition + supplementLength) < endOffset)
                ? supplementLength : (endOffset - nPosition);
            final int windowMax = nPosition + maxMatchLen;
            final int windowStart = nPosition;
            int m = 0;
            int inserted = 0;
            int matchPos;
            int maxForwardPos = nPosition + 2 + 1 + c.search.forwardSearchDistance;

            if (maxForwardPos > (endOffset - 2)) {
                maxForwardPos = endOffset - 2;
            }

            while (m < matchesPerPosition && c.matchLen[matchBase + m] != 0) {
                offsetCache[c.matchOff[matchBase + m] & 2047] = nPosition;
                offsetCache[(c.matchOff[matchBase + m] - c.matchDepth[matchBase + m]) & 2047] = nPosition;
                m++;
            }

            for (matchPos = nextOffsetForPos[nPosition - prevBlockSize];
                    m < matchesPerPosition && matchPos >= 0;
                    matchPos = nextOffsetForPos[matchPos - prevBlockSize]) {
                final int matchOffset = nPosition - matchPos;

                if (matchOffset > c.maxOffset) {
                    break;
                }

                int alreadyExists = 0;
                if (offsetCache[matchOffset & 2047] == nPosition) {
                    for (int existing = 0; existing < m; existing++) {
                        if (c.matchOff[matchBase + existing] == matchOffset
                                || (c.matchOff[matchBase + existing] - c.matchDepth[matchBase + existing]) == matchOffset) {
                            alreadyExists = 1;
                            break;
                        }
                    }
                }

                if (alreadyExists != 0) {
                    continue;
                }

                int forwardPos = nPosition + 2 + 1;
                if (forwardPos < matchOffset) {
                    continue;
                }

                while (forwardPos < maxForwardPos && w[forwardPos] != w[forwardPos - matchOffset]) {
                    forwardPos++;
                }

                if (forwardPos >= maxForwardPos) {
                    continue;
                }

                final int len0 = rleLen[matchPos];
                final int len1 = rleLen[nPosition];
                final int minLen = (len0 < len1) ? len0 : len1;
                int atPos = windowStart + minLen;

                if (atPos > windowMax) {
                    atPos = windowMax;
                }

                while ((atPos + 8) < windowMax && matches8(w, atPos, atPos - matchOffset)) {
                    atPos += 8;
                }
                while ((atPos + 4) < windowMax && matches4(w, atPos, atPos - matchOffset)) {
                    atPos += 4;
                }
                while (atPos < windowMax && w[atPos] == w[atPos - matchOffset]) {
                    atPos++;
                }

                c.matchLen[matchBase + m] = limitMatchLenLocal(atPos - windowStart);
                c.matchOff[matchBase + m] = matchOffset;
                c.matchDepth[matchBase + m] = 0;
                m++;
                if (c.collectDiagnostics && m > c.diagnostics.maxMatchesReached) {
                    c.diagnostics.maxMatchesReached = m;
                }
                if (c.collectDiagnostics && m == matchesPerPosition) {
                    c.diagnostics.matchPositionsAtLimit++;
                }

                insertForwardMatch(c, w, nPosition, matchOffset, prevBlockSize, endOffset, 8);

                inserted++;
                if (inserted >= c.search.forwardInsertLimit) {
                    break;
                }
            }
        }

        /* Pick final matches */
        optimizeForward(c, w, prevBlockSize, endOffset, 0 /* insertForwardReps */,
            curRepMatchOffset, c.maxArrivalsPerPosition, blockFlags);

        /* Apply reduction and merge pass */
        int didReduce;
        int passes = 0;
        do {
            didReduce = reduceCommands(c, w, prevBlockSize, endOffset, curRepMatchOffset, blockFlags);
            passes++;
        } while (didReduce != 0 && passes < c.search.reductionPasses);

        /* Write compressed block */
        return writeBlock(c, w, prevBlockSize, endOffset, out, base, maxOutDataSize, bits,
            finalLiterals, curRepMatchOffset, blockFlags);
    }

    /* ------------------------------------------------------------------ */
    /* Context lifecycle and block driver                                  */
    /* ------------------------------------------------------------------ */

    public long getMaxCompressedSize(long inputSize) {
        return ((inputSize + 65535) >> 16) * 128 + inputSize;
    }

    int compressorInit(Compressor c, int blockSize, int maxWindowSize, int maxOffset,
            int flags, KcSearch.Config searchConfig) {
        c.sa = new int[maxWindowSize];
        c.intervals = new long[maxWindowSize];
        c.posData = new long[maxWindowSize];
        c.posDataInt = new int[maxWindowSize];
        c.rleLen = new int[maxWindowSize];
        c.visited = new int[maxWindowSize];
        c.openIntervals = new long[LCP_AND_TAG_MAX + 1];

        final int arrivals = searchConfig.maxArrivalsPerPosition;
        final int arrivalSlots = (blockSize + 1) * arrivals;
        c.arrCost = new int[arrivalSlots];
        c.arrFromPos = new int[arrivalSlots];
        c.arrFromSlot = new int[arrivalSlots];
        c.arrRepOffset = new int[arrivalSlots];
        c.arrRepPos = new int[arrivalSlots];
        c.arrMatchLen = new int[arrivalSlots];
        c.arrNumLiterals = new int[arrivalSlots];
        c.arrScore = new int[arrivalSlots];

        c.bestLen = new int[blockSize];
        c.bestOff = new int[blockSize];

        final int matchSlots = blockSize * searchConfig.maxMatchesPerPosition;
        c.matchLen = new int[matchSlots];
        c.matchOff = new int[matchSlots];
        c.matchDepth = new int[matchSlots];

        c.firstOffsetForByte = new int[65536];
        c.nextOffsetForPos = new int[blockSize];
        c.offsetCache = new int[2048];
        c.repMatchArrivalIdx = new int[arrivals * 2 + 1];

        if ((flags & FLG_IS_BACKWARD) != 0) {
            c.flags = flags & (~FLG_IS_INVERTED);
        } else {
            c.flags = flags;
        }
        c.blockSize = blockSize;
        c.maxOffset = maxOffset != 0 ? maxOffset : KcFormat.MAX_OFFSET;
        c.search = new KcSearch.Config(searchConfig);
        c.maxArrivalsPerPosition = searchConfig.maxArrivalsPerPosition;
        c.collectDiagnostics = false;

        c.stats = new Stats();
        c.diagnostics = new KcSearch.Diagnostics();
        c.stats.minMatchLen = -1;
        c.stats.minOffset = -1;
        c.stats.minRle1Len = -1;
        c.stats.minRle2Len = -1;

        return 0;
    }

    int shrinkBlock(Compressor c, byte[] window, int prevBlockSize, int inDataSize,
            Out out, int base, int maxOutDataSize, BitState bits, int[] finalLiterals,
            int[] curRepMatchOffset, int blockFlags) {
        if (KcMatchFinder.buildSuffixArray(c, window, prevBlockSize + inDataSize) != 0) {
            return -1;
        }
        if (prevBlockSize != 0) {
            KcMatchFinder.skipMatches(c, 0, prevBlockSize);
        }
        KcMatchFinder.findAllMatches(c, c.search.maxMatchesPerPosition,
            prevBlockSize, prevBlockSize + inDataSize);

        return optimizeAndWriteBlock(c, window, prevBlockSize, inDataSize, out, base,
            maxOutDataSize, bits, finalLiterals, curRepMatchOffset, blockFlags);
    }

    /**
     * Compress memory. Returns the compressed size, or -1 on error.
     *
     * @param statsOut optional receiver for compression statistics (may be null)
     */
    public long compress(byte[] input, int inputSize, byte[] outBuffer, int maxOutBufferSize,
            KcSearch.Config searchConfig, int flags, int maxOffset, int dictionarySize,
            Stats statsOut) {
        if (input == null || inputSize < 1 || inputSize > 65535 ||
                inputSize > input.length || outBuffer == null ||
                maxOutBufferSize < 0 || maxOutBufferSize > outBuffer.length ||
                dictionarySize != 0) {
            throw new IllegalArgumentException("encoder requires 1..65535 bytes without a dictionary");
        }
        final int blockSize = (inputSize < KcFormat.BLOCK_SIZE)
            ? ((inputSize < 1024) ? 1024 : inputSize) : KcFormat.BLOCK_SIZE;
        final int maxOutBlockSize = (int) getMaxCompressedSize(blockSize);

        Compressor compressor = new Compressor();
        compressorInit(compressor, blockSize, blockSize * 2, maxOffset, flags, searchConfig);

        Out out = new Out(outBuffer);
        BitState bits = new BitState();

        long originalSize = 0;
        long compressedSize = 0;
        int prevBlockSize = 0;
        int numBlocks = 0;
        int curBitsOffset = 0;
        int curBitShift = -1;
        int curFinalLiterals = 0;
        int blockFlags = 1;
        int curRepMatchOffset = 1;
        boolean error = false;

        if (dictionarySize != 0) {
            originalSize = dictionarySize;
            prevBlockSize = dictionarySize;
        }

        int[] finalLiteralsBox = new int[1];
        int[] repOffsetBox = new int[1];

        while (originalSize < inputSize && !error) {
            int inDataSize = (int) (inputSize - originalSize);
            if (inDataSize > blockSize) {
                inDataSize = blockSize;
            }

            if (inDataSize > 0) {
                int outDataEnd = (int) (maxOutBufferSize - compressedSize);
                if (outDataEnd > maxOutBlockSize) {
                    outDataEnd = maxOutBlockSize;
                }

                if ((originalSize + inDataSize) >= inputSize) {
                    blockFlags |= 2;
                }

                final int windowBase = (int) (originalSize - prevBlockSize);
                final int windowLen = prevBlockSize + inDataSize;
                byte[] window = new byte[windowLen];
                System.arraycopy(input, windowBase, window, 0, windowLen);

                finalLiteralsBox[0] = curFinalLiterals;
                repOffsetBox[0] = curRepMatchOffset;
                bits.curBitsOffset = curBitsOffset;
                bits.curBitShift = curBitShift;

                final int outDataSize = shrinkBlock(compressor, window, prevBlockSize, inDataSize,
                    out, (int) compressedSize, outDataEnd, bits, finalLiteralsBox, repOffsetBox,
                    blockFlags);

                curBitsOffset = bits.curBitsOffset;
                curBitShift = bits.curBitShift;
                curFinalLiterals = finalLiteralsBox[0];
                curRepMatchOffset = repOffsetBox[0];
                blockFlags &= ~1;

                if (outDataSize >= 0 && curFinalLiterals >= 0 && curFinalLiterals < inDataSize) {
                    inDataSize -= curFinalLiterals;
                    originalSize += inDataSize;
                    curFinalLiterals = 0;
                    compressedSize += outDataSize;
                    if (curBitShift != -1) {
                        curBitsOffset -= outDataSize;
                    }
                } else {
                    error = true;
                }

                prevBlockSize = inDataSize;
                numBlocks++;
            }
        }

        if (statsOut != null) {
            Stats s = compressor.stats;
            statsOut.numEod = s.numEod;
            statsOut.numLiterals = s.numLiterals;
            statsOut.numNormalMatches = s.numNormalMatches;
            statsOut.numRepMatches = s.numRepMatches;
            statsOut.safeDist = s.safeDist;
            statsOut.minLiterals = s.minLiterals;
            statsOut.maxLiterals = s.maxLiterals;
            statsOut.totalLiterals = s.totalLiterals;
            statsOut.nonzeroLiteralRuns = s.nonzeroLiteralRuns;
            statsOut.minOffset = s.minOffset;
            statsOut.maxOffset = s.maxOffset;
            statsOut.totalOffsets = s.totalOffsets;
            statsOut.minMatchLen = s.minMatchLen;
            statsOut.maxMatchLen = s.maxMatchLen;
            statsOut.totalMatchLens = s.totalMatchLens;
            statsOut.minRle1Len = s.minRle1Len;
            statsOut.maxRle1Len = s.maxRle1Len;
            statsOut.totalRle1Lens = s.totalRle1Lens;
            statsOut.minRle2Len = s.minRle2Len;
            statsOut.maxRle2Len = s.maxRle2Len;
            statsOut.totalRle2Lens = s.totalRle2Lens;
            statsOut.commandsDivisor = s.commandsDivisor;
            statsOut.literalsDivisor = s.literalsDivisor;
            statsOut.matchDivisor = s.matchDivisor;
            statsOut.rle1Divisor = s.rle1Divisor;
            statsOut.rle2Divisor = s.rle2Divisor;
        }

        return error ? -1 : compressedSize;
    }
}
