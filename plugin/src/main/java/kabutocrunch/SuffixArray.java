/*
 * Suffix array construction (SA-IS, Nong/Zhang/Chan induced sorting).
 *
 * The C encoder obtains its suffix array from libdivsufsort. A suffix array is
 * uniquely determined by the input, so any correct construction yields exactly
 * the same array as libdivsufsort and therefore bit-identical compression.
 * This class replaces the libdivsufsort dependency; plugin/README.md records
 * the equivalence check that is run against the C encoder.
 */
package kabutocrunch;

import java.util.Arrays;

final class SuffixArray {

    private SuffixArray() {
    }

    /**
     * Builds the suffix array of {@code input} into {@code sa}.
     *
     * @param input  data bytes
     * @param n      number of bytes to consider
     * @param sa     output array of length >= n
     */
    public static void build(byte[] input, int n, int[] sa) {
        if (n <= 0) {
            return;
        }
        if (n == 1) {
            sa[0] = 0;
            return;
        }
        if (n == 2) {
            boolean m = (input[0] & 0xff) < (input[1] & 0xff);
            sa[m ? 0 : 1] = 0;
            sa[m ? 1 : 0] = 1;
            return;
        }

        /* Append a unique sentinel smaller than any real byte. */
        int[] s = new int[n + 1];
        for (int i = 0; i < n; i++) {
            s[i] = (input[i] & 0xff) + 1;
        }
        s[n] = 0;

        int[] tmp = new int[n + 1];
        sais(s, tmp, n + 1, 257);

        /* tmp[0] is the sentinel suffix; shift the rest down. */
        System.arraycopy(tmp, 1, sa, 0, n);
    }

    /**
     * SA-IS. Requires {@code s[n-1]} to be the unique smallest value (the
     * sentinel) with all other values in {@code [1, K]}.
     */
    private static void sais(int[] s, int[] sa, int n, int K) {
        if (n == 1) {
            sa[0] = 0;
            return;
        }

        boolean[] st = new boolean[n];
        st[n - 1] = true;
        for (int i = n - 2; i >= 0; i--) {
            st[i] = (s[i] < s[i + 1]) || (s[i] == s[i + 1] && st[i + 1]);
        }

        int n1 = 0;
        for (int i = 1; i < n; i++) {
            if (st[i] && !st[i - 1]) {
                n1++;
            }
        }

        int[] bkt = new int[K + 2];

        Arrays.fill(sa, 0, n, -1);
        getBuckets(s, bkt, n, K, true);
        for (int i = n - 1; i >= 0; i--) {
            if (isLms(st, i)) {
                sa[--bkt[s[i]]] = i;
            }
        }
        induceSAl(st, sa, s, bkt, n, K);
        induceSAs(st, sa, s, bkt, n, K);

        int m = 0;
        for (int i = 0; i < n; i++) {
            if (isLms(st, sa[i])) {
                sa[m++] = sa[i];
            }
        }
        for (int i = m; i < n; i++) {
            sa[i] = -1;
        }

        /* LMS positions in text order, needed to build the reduced string. */
        int[] lmsPos = new int[n1];
        int[] lmsRank = new int[n];
        Arrays.fill(lmsRank, 0, n, -1);
        int idx = 0;
        for (int i = 1; i < n; i++) {
            if (isLms(st, i)) {
                lmsPos[idx] = i;
                lmsRank[i] = idx;
                idx++;
            }
        }

        int[] s1 = new int[n1];
        int name = 0;
        int prev = -1;
        for (int i = 0; i < n1; i++) {
            int pos = sa[i];
            boolean diff = false;
            if (prev == -1) {
                diff = true;
            } else {
                int d = 0;
                while (true) {
                    if (s[pos + d] != s[prev + d] || st[pos + d] != st[prev + d]) {
                        diff = true;
                        break;
                    }
                    if (d > 0 && (isLms(st, pos + d) || isLms(st, prev + d))) {
                        break;
                    }
                    d++;
                }
            }
            if (diff) {
                name++;
                prev = pos;
            }
            s1[lmsRank[pos]] = name - 1;
        }

        int[] sa1 = new int[n1];
        if (name < n1) {
            sais(s1, sa1, n1, name - 1);
        } else {
            for (int i = 0; i < n1; i++) {
                sa1[s1[i]] = i;
            }
        }

        /* Stage 3: induce the final suffix array. */
        Arrays.fill(sa, 0, n, -1);
        getBuckets(s, bkt, n, K, true);
        for (int i = n1 - 1; i >= 0; i--) {
            int pos = lmsPos[sa1[i]];
            sa[--bkt[s[pos]]] = pos;
        }
        induceSAl(st, sa, s, bkt, n, K);
        induceSAs(st, sa, s, bkt, n, K);
    }

    private static boolean isLms(boolean[] st, int i) {
        return i > 0 && st[i] && !st[i - 1];
    }

    private static void getBuckets(int[] s, int[] bkt, int n, int K, boolean end) {
        for (int i = 0; i <= K; i++) {
            bkt[i] = 0;
        }
        for (int i = 0; i < n; i++) {
            bkt[s[i]]++;
        }
        int sum = 0;
        for (int i = 0; i <= K; i++) {
            int c = bkt[i];
            sum += c;
            bkt[i] = end ? sum : sum - c;
        }
    }

    private static void induceSAl(boolean[] st, int[] sa, int[] s, int[] bkt, int n, int K) {
        getBuckets(s, bkt, n, K, false);
        for (int i = 0; i < n; i++) {
            int j = sa[i] - 1;
            if (j >= 0 && !st[j]) {
                sa[bkt[s[j]]++] = j;
            }
        }
    }

    private static void induceSAs(boolean[] st, int[] sa, int[] s, int[] bkt, int n, int K) {
        getBuckets(s, bkt, n, K, true);
        for (int i = n - 1; i >= 0; i--) {
            int j = sa[i] - 1;
            if (j >= 0 && st[j]) {
                sa[--bkt[s[j]]] = j;
            }
        }
    }
}
