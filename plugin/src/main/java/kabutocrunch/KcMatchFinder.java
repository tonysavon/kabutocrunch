/*
 * matchfinder.c - LZ match finder implementation
 *
 * The following copying information applies to this specific source code file:
 *
 * Written in 2019-2021 by Emmanuel Marty <marty.emmanuel@gmail.com>
 * Portions written in 2014-2015 by Eric Biggers <ebiggers3@gmail.com>
 *
 * To the extent possible under law, the author(s) have dedicated all copyright
 * and related and neighboring rights to this software to the public domain
 * worldwide via the Creative Commons Zero 1.0 Universal Public Domain
 * Dedication (the "CC0").
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the CC0 for more details.
 *
 * You should have received a copy of the CC0 along with this software; if not
 * see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
/*
 * LZ match finder built on the suffix array and LCP intervals.
 *
 * Java port of src/kc_matchfinder.c (wimlib-derived interval match finder).
 */
package kabutocrunch;

final class KcMatchFinder {

    private KcMatchFinder() {
    }

    private static int getIndexTag(int index) {
        /* 11400714819323198485 as an unsigned 64-bit constant (0x9E3779B97F4A7C15). */
        return (int) ((((long) index) * -7046029254386353131L) >>> (64 - KcShrink.TAG_BITS));
    }

    public static int buildSuffixArray(KcShrink.Compressor c, byte[] w, int n) {
        final long[] intervals = c.intervals;
        final int[] sa = c.sa;

        SuffixArray.build(w, n, sa);
        for (int i = n - 1; i >= 0; i--) {
            intervals[i] = sa[i] & 0xFFFFFFFFL;
        }

        final int[] plcp = c.posDataInt; /* reused as PLCP/Phi */
        int curLen = 0;

        plcp[(int) (intervals[0] & KcShrink.POS_MASK)] = -1;
        for (int i = 1; i < n; i++) {
            plcp[(int) (intervals[i] & KcShrink.POS_MASK)] = (int) (intervals[i - 1] & 0xFFFFFFFFL);
        }
        for (int i = 0; i < n; i++) {
            if (plcp[i] == -1) {
                plcp[i] = 0;
                continue;
            }
            final int maxLen = (i > plcp[i]) ? (n - i) : (n - plcp[i]);
            while (curLen < maxLen && w[i + curLen] == w[plcp[i] + curLen]) {
                curLen++;
            }
            plcp[i] = curLen;
            if (curLen > 0) {
                curLen--;
            }
        }

        intervals[0] &= KcShrink.POS_MASK;

        for (int i = 1; i < n; i++) {
            final int index = (int) (intervals[i] & KcShrink.POS_MASK);
            int len = plcp[index];
            if (len < KcFormat.MIN_MATCH_SIZE) {
                len = 0;
            }
            if (len > KcShrink.LCP_MAX) {
                len = KcShrink.LCP_MAX;
            }
            int taggedLen = 0;
            if (len != 0) {
                taggedLen = (len << KcShrink.TAG_BITS)
                    | (getIndexTag(index) & ((1 << KcShrink.TAG_BITS) - 1));
            }
            intervals[i] = ((long) index) | (((long) taggedLen) << KcShrink.LCP_SHIFT);
        }

        /* Build intervals for finding matches (wimlib methodology). */
        final long[] posData = c.posData;
        final long[] openIntervals = c.openIntervals;
        final int minMatchSize = KcFormat.MIN_MATCH_SIZE;
        long nextIntervalIdx;
        int topIdx = 0;
        long prevPos = intervals[0] & KcShrink.POS_MASK;

        openIntervals[0] = 0;
        intervals[0] = 0;
        nextIntervalIdx = 1;

        for (int r = 1; r < n; r++) {
            final long nextPos = intervals[r] & KcShrink.POS_MASK;
            final long nextLcp = intervals[r] & KcShrink.LCP_MASK;
            final long topLcp = openIntervals[topIdx] & KcShrink.LCP_MASK;

            if (nextLcp == topLcp) {
                posData[(int) prevPos] = openIntervals[topIdx];
            } else if (nextLcp > topLcp) {
                topIdx++;
                openIntervals[topIdx] = nextLcp | nextIntervalIdx++;
                posData[(int) prevPos] = openIntervals[topIdx];
            } else {
                posData[(int) prevPos] = openIntervals[topIdx];
                for (;;) {
                    final long closedIdx = openIntervals[topIdx--] & KcShrink.POS_MASK;
                    final long superLcp = openIntervals[topIdx] & KcShrink.LCP_MASK;

                    if (nextLcp == superLcp) {
                        intervals[(int) closedIdx] = openIntervals[topIdx];
                        break;
                    } else if (nextLcp > superLcp) {
                        topIdx++;
                        openIntervals[topIdx] = nextLcp | nextIntervalIdx++;
                        intervals[(int) closedIdx] = openIntervals[topIdx];
                        break;
                    } else {
                        intervals[(int) closedIdx] = openIntervals[topIdx];
                    }
                }
            }
            prevPos = nextPos;
        }

        posData[(int) prevPos] = openIntervals[topIdx];
        for (; topIdx > 0; topIdx--) {
            intervals[(int) (openIntervals[topIdx] & KcShrink.POS_MASK)] = openIntervals[topIdx - 1];
        }

        return 0;
    }

    static int limitMatchLen(int len) {
        return len;
    }

    /** Mutable state for one find-matches scan. */
    private static final class Scan {
        int matchCount;
        int depthCount;
        int prevOffset;
        int prevLen;
        int curDepth;
        int curDepthIdx = -1;
    }

    private static void emit(Scan st, KcShrink.Compressor c, int outBase, int matchOffset, int matchLen) {
        if (st.prevLen > 2 && st.prevOffset != 0 && matchOffset == (st.prevOffset - 1)
                && matchLen == (st.prevLen - 1) && st.curDepthIdx >= 0 && st.curDepth < KcShrink.LCP_MAX) {
            c.matchDepth[st.curDepthIdx] = ++st.curDepth;
        } else {
            c.matchLen[outBase + st.matchCount] = limitMatchLen(matchLen);
            c.matchOff[outBase + st.matchCount] = matchOffset;
            st.matchCount++;

            c.matchDepth[outBase + st.depthCount] = 0;
            st.curDepth = 0;
            st.curDepthIdx = outBase + st.depthCount;
            st.depthCount++;
        }

        st.prevLen = matchLen;
        st.prevOffset = matchOffset;
    }

    /**
     * Find matches at one window offset, writing into the compressor's match
     * arrays at outBase. Returns the number of matches found.
     */
    static int findMatchesAt(KcShrink.Compressor c, int offset, int outBase, int maxMatches) {
        final long[] intervals = c.intervals;
        final long[] posData = c.posData;
        final int maxOffset = c.maxOffset;
        final Scan st = new Scan();
        final int shift = KcShrink.LCP_SHIFT + KcShrink.TAG_BITS;
        long ref;
        long superRef;
        long matchPos;

        ref = posData[offset];
        posData[offset] = 0;

        while (((superRef = intervals[(int) (ref & KcShrink.POS_MASK)]) & KcShrink.LCP_MASK) != 0) {
            intervals[(int) (ref & KcShrink.POS_MASK)] = offset | KcShrink.VISITED_FLAG;
            ref = superRef;
        }

        if (superRef == 0) {
            if (ref != 0) {
                intervals[(int) (ref & KcShrink.POS_MASK)] = offset | KcShrink.VISITED_FLAG;
            }
            return 0;
        }

        matchPos = superRef & KcShrink.EXCL_VISITED_MASK;

        if (st.matchCount < maxMatches) {
            final int matchOffset = (int) (offset - matchPos);
            if (matchOffset <= maxOffset) {
                final int matchLen = (int) (ref >>> shift);
                c.matchLen[outBase] = limitMatchLen(matchLen);
                c.matchOff[outBase] = matchOffset;
                st.matchCount++;
                c.matchDepth[outBase] = 0;
                st.curDepth = 0;
                st.curDepthIdx = outBase;
                st.depthCount++;
                st.prevLen = matchLen;
                st.prevOffset = matchOffset;
            }
        }

        for (;;) {
            if ((superRef = posData[(int) matchPos]) > ref) {
                matchPos = intervals[(int) (superRef & KcShrink.POS_MASK)] & KcShrink.EXCL_VISITED_MASK;
                if (st.matchCount < maxMatches) {
                    final int matchOffset = (int) (offset - matchPos);
                    if (matchOffset <= maxOffset) {
                        emit(st, c, outBase, matchOffset, (int) (ref >>> shift));
                    }
                }
            }

            while ((superRef = posData[(int) matchPos]) > ref) {
                matchPos = intervals[(int) (superRef & KcShrink.POS_MASK)] & KcShrink.EXCL_VISITED_MASK;
            }

            intervals[(int) (ref & KcShrink.POS_MASK)] = offset | KcShrink.VISITED_FLAG;
            posData[(int) matchPos] = ref;

            if (st.matchCount < maxMatches) {
                final int matchOffset = (int) (offset - matchPos);
                if (matchOffset <= maxOffset && matchOffset != st.prevOffset) {
                    emit(st, c, outBase, matchOffset, (int) (ref >>> shift));
                }
            }

            if (superRef == 0) {
                break;
            }
            ref = superRef;
            matchPos = intervals[(int) (ref & KcShrink.POS_MASK)] & KcShrink.EXCL_VISITED_MASK;

            if (st.matchCount < maxMatches) {
                final int matchOffset = (int) (offset - matchPos);
                if (matchOffset <= maxOffset) {
                    emit(st, c, outBase, matchOffset, (int) (ref >>> shift));
                }
            }
        }

        return st.matchCount;
    }

    /** Skip previously compressed bytes (also performs the lazy interval update). */
    public static void skipMatches(KcShrink.Compressor c, int startOffset, int endOffset) {
        for (int i = startOffset; i < endOffset; i++) {
            findMatchesAt(c, i, 0, 0);
        }
    }

    /** Find all matches for the data to be compressed. */
    public static void findAllMatches(KcShrink.Compressor c, int matchesPerOffset,
            int startOffset, int endOffset) {
        int base = 0;

        for (int i = startOffset; i < endOffset; i++) {
            final int numMatches = findMatchesAt(c, i, base, matchesPerOffset);

            if (c.collectDiagnostics) {
                c.diagnostics.matchCandidatesRetained += numMatches;
                if (numMatches > c.diagnostics.maxMatchesReached) {
                    c.diagnostics.maxMatchesReached = numMatches;
                }
                if (numMatches == matchesPerOffset) {
                    c.diagnostics.matchPositionsAtLimit++;
                }
            }

            if (numMatches < matchesPerOffset) {
                for (int j = numMatches; j < matchesPerOffset; j++) {
                    c.matchLen[base + j] = 0;
                    c.matchOff[base + j] = 0;
                    c.matchDepth[base + j] = 0;
                }
            }

            base += matchesPerOffset;
        }
    }
}
