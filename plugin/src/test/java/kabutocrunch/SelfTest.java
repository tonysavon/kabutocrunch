/*
 * Self-checks for the Java port that do not need the C encoder.
 *
 * Currently covers the SA-IS suffix array against a brute-force reference.
 */
package kabutocrunch;

import java.util.Arrays;
import java.util.Random;

public final class SelfTest {

    private SelfTest() {
    }

    public static void main(String[] args) {
        runSuffixArrayChecks();
    }

    private static void runSuffixArrayChecks() {
        int failures = 0;
        failures += checkBitCodes();
        Random rnd = new Random(12345);

        for (int iteration = 0; iteration < 400; iteration++) {
            int n = 1 + rnd.nextInt(60);
            int alphabet = 1 + rnd.nextInt(4);
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) rnd.nextInt(alphabet);
            }
            if (!compareWithBruteForce(data, n)) {
                failures++;
                System.out.println("FAIL random n=" + n + " alphabet=" + alphabet);
            }
        }

        for (int n = 1; n <= 200; n++) {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) (i & 0xff);
            }
            if (!compareWithBruteForce(data, n)) {
                failures++;
                System.out.println("FAIL ascending n=" + n);
            }
            Arrays.fill(data, (byte) 7);
            if (!compareWithBruteForce(data, n)) {
                failures++;
                System.out.println("FAIL constant n=" + n);
            }
        }

        /* Three bytes, all alphabet combinations. */
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                for (int c = 0; c < 3; c++) {
                    byte[] data = {(byte) a, (byte) b, (byte) c};
                    if (!compareWithBruteForce(data, 3)) {
                        failures++;
                        System.out.println("FAIL tri " + a + b + c);
                    }
                }
            }
        }

        if (failures != 0) {
            System.out.println("suffix array self-test FAILED (" + failures + " cases)");
            System.exit(1);
        }
        System.out.println("suffix array self-test passed");
    }

    private static boolean compareWithBruteForce(byte[] data, int n) {
        int[] expected = new int[n];
        for (int i = 0; i < n; i++) {
            expected[i] = i;
        }
        /* Insertion sort by suffix comparison; n is small here. */
        for (int i = 1; i < n; i++) {
            int key = expected[i];
            int j = i - 1;
            while (j >= 0 && compareSuffixes(data, expected[j], key, n) > 0) {
                expected[j + 1] = expected[j];
                j--;
            }
            expected[j + 1] = key;
        }

        int[] actual = new int[n];
        SuffixArray.build(data, n, actual);
        for (int i = 0; i < n; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int compareSuffixes(byte[] data, int left, int right, int n) {
        while (left < n && right < n) {
            int a = data[left] & 0xff;
            int b = data[right] & 0xff;
            if (a != b) {
                return a - b;
            }
            left++;
            right++;
        }
        return (n - left) - (n - right);
    }

    /** Round-trip the Dali interlaced Elias code through its writer and reader. */
    private static int checkBitCodes() {
        int failures = 0;
        int[] bytes = new int[4096];
        int[] count = new int[1];
        int[] shift = new int[] {7};
        int[] cursor = new int[1];

        for (int skip = 0; skip <= 1; skip++) {
            /* With skip != 0 the first bit is not emitted, so value 1 encodes to
             * zero bits and cannot be read back standalone. */
            for (int value = (skip != 0 ? 2 : 1); value <= 70000;
                    value += (value < 300 ? 1 : 97)) {
                shift[0] = 7;
                cursor[0] = 0;
                count[0] = 1;
                bytes[0] = 0;
                int written = KcFormat.writeDaliLength(value, skip, bit -> {
                    if (shift[0] < 0) {
                        shift[0] = 7;
                        cursor[0]++;
                        bytes[cursor[0]] = 0;
                        count[0] = cursor[0] + 1;
                    }
                    if (bit != 0) {
                        bytes[cursor[0]] |= 1 << shift[0];
                    }
                    shift[0]--;
                    return 1;
                });
                if (written == 0) {
                    failures++;
                    continue;
                }

                int[] bitPos = new int[] {0};
                KcFormat.GetBit reader = out -> {
                    int byteIndex = bitPos[0] >> 3;
                    int bitIndex = 7 - (bitPos[0] & 7);
                    if (byteIndex >= count[0]) {
                        return 0;
                    }
                    out[0] = ((bytes[byteIndex] >> bitIndex) & 1);
                    bitPos[0]++;
                    return 1;
                };

                int[] decoded = new int[1];
                if (!KcFormat.readDaliLength(skip, reader, decoded) || decoded[0] != value) {
                    failures++;
                    if (failures < 6) {
                        System.out.println("FAIL bitcode skip=" + skip + " value=" + value
                            + " decoded=" + decoded[0]);
                    }
                }
            }
        }
        return failures;
    }
}
