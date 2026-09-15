package kabutocrunch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import kickass.plugins.interf.general.IMemoryBlock;

/** Test driver only: not packaged in the plugin JAR. */
public final class PluginTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("accepted invalid input");
    }

    private static IMemoryBlock block(int address, byte... bytes) {
        return new IMemoryBlock() {
            public int getStartAddress() { return address; }
            public byte[] getBytes() { return bytes; }
            public String getName() { return "test"; }
        };
    }

    private static void roundTrip(byte[] data, byte[] packed, boolean dali) {
        byte[] restored = new byte[data.length];
        Decoder.Result result = dali ? Decoder.decodeDali(packed, packed.length, restored, restored.length)
                : Decoder.decode(packed, packed.length, restored, restored.length);
        check(result.ok && result.outSize == data.length && Arrays.equals(data, restored),
                "round trip: " + result.error);
    }

    private static void selfTest() throws Exception {
        SelfTest.main(new String[0]);
        IMemoryBlock high = block(0x4000, (byte) 4, (byte) 5);
        IMemoryBlock low = block(0x1000, (byte) 1, (byte) 2, (byte) 3);
        List<IMemoryBlock> blocks = List.of(high, low);
        KABUTO.Image image = KABUTO.merge(blocks);
        check(blocks.get(0) == high, "merge mutated caller's order");
        check(image.address == 0x1000 && image.bytes.length == 0x3002, "merged bounds");
        check(image.bytes[0] == 1 && image.bytes[0x3001] == 5, "merged bytes");
        for (int i = 3; i < 0x3000; i++) check(image.bytes[i] == 0, "gap not zero");
        check(KABUTO.merge(List.of(block(0xffff, (byte) 7))).bytes[0] == 7, "last address");
        check(KABUTO.merge(List.of(block(1, new byte[65535]))).bytes.length == 65535, "max span");
        rejects(() -> KABUTO.merge(List.of()));
        rejects(() -> KABUTO.merge(List.of(block(0x1000))));
        rejects(() -> KABUTO.merge(List.of(low, low)));
        rejects(() -> KABUTO.merge(List.of(low, block(0x1001, (byte) 9))));
        rejects(() -> KABUTO.merge(List.of(block(-1, (byte) 1))));
        rejects(() -> KABUTO.merge(List.of(block(65535, (byte) 1, (byte) 2))));
        rejects(() -> KABUTO.merge(List.of(block(0, (byte) 1), block(65535, (byte) 2))));
        rejects(() -> Encoder.compress(new byte[0], false, 2));
        rejects(() -> Encoder.compress(new byte[65536], false, 2));
        rejects(() -> Encoder.compress(new byte[]{1}, false, -1));
        rejects(() -> Encoder.compress(new byte[]{1}, false, 16));
        int[] value = new int[1];
        check(!KcFormat.readDaliLength(0, bit -> { bit[0] = 0; return 1; }, value), "length overflow");
        check(!Decoder.decode(new byte[]{1}, 2, new byte[1], 1).ok, "input bounds");
        check(!Decoder.decode(new byte[]{1}, 1, new byte[1], 2).ok, "output bounds");
        // The previously missing state transition requires nontrivial new-offset matches.
        byte[] data = new byte[4096];
        new Random(921).nextBytes(data);
        System.arraycopy(data, 0, data, 1000, 1000);
        byte[][] reference = new byte[4][];
        for (int i = 0; i < 4; i++) {
            reference[i] = Encoder.compress(data, i >= 2, (i & 1) == 0 ? 0 : 15);
            roundTrip(data, reference[i], i >= 2);
        }
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<Callable<Boolean>>();
            for (int j = 0; j < 12; j++) {
                final int i = j % 4;
                tasks.add(() -> Arrays.equals(reference[i], Encoder.compress(data, i >= 2, (i & 1) == 0 ? 0 : 15)));
            }
            for (var result : pool.invokeAll(tasks)) check(result.get(), "shared configuration");
        } finally {
            pool.shutdownNow();
        }
        System.out.println("PASS: plugin core, decoder regressions and concurrent calls");
    }

    public static void main(String[] args) throws Exception {
        selfTest();
        if (args.length == 0) return;
        int count = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] fields = line.split("\t");
            byte[] data = Files.readAllBytes(Path.of(fields[0]));
            boolean dali = Boolean.parseBoolean(fields[2]);
            byte[] packed = Encoder.compress(data, dali, Integer.parseInt(fields[3]));
            roundTrip(data, packed, dali);
            Files.write(Path.of(fields[1]), packed);
            count++;
        }
        System.out.println("PASS: " + count + " encoder round trips");
    }
}
