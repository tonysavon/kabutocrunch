/* Copyright (c) 2026 Kabutocrunch contributors.
 * Distributed under the zlib license; see LICENSE. */
package kabutocrunch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kickass.plugins.interf.general.IEngine;
import kickass.plugins.interf.general.IMemoryBlock;
import kickass.plugins.interf.general.IValue;
import kickass.plugins.interf.modifier.IModifier;
import kickass.plugins.interf.modifier.ModifierDefinition;

/** .modify KABUTO(raw, dali, speed), defaulting to (true, false, 2). */
public final class KABUTO implements IModifier {
    @Override
    public ModifierDefinition getDefinition() {
        ModifierDefinition definition = new ModifierDefinition();
        definition.setName("KABUTO");
        return definition;
    }

    @Override
    public byte[] execute(List<IMemoryBlock> blocks, IValue[] values, IEngine engine) {
        try {
            if (values.length > 3) {
                throw new IllegalArgumentException("expected KABUTO(raw, dali, speed), at most 3 arguments");
            }
            boolean raw = booleanArgument(values, 0, true, "raw");
            boolean dali = booleanArgument(values, 1, false, "dali");
            int speed = 2;
            if (values.length > 2) {
                if (!values[2].hasIntRepresentation() ||
                        (values[2].hasDoubleRepresentation() && values[2].getDouble() != values[2].getInt())) {
                    throw new IllegalArgumentException("speed must be an integer in 0..15");
                }
                speed = values[2].getInt();
            }
            if (speed < 0 || speed > 15) {
                throw new IllegalArgumentException("speed must be in 0..15");
            }
            Image image = merge(blocks);
            byte[] packed = Encoder.compress(image.bytes, dali, speed);
            if (raw) {
                return packed;
            }
            // The ASM Mem entry point reads the destination high byte first.
            byte[] mem = new byte[packed.length + 2];
            mem[0] = (byte) (image.address >>> 8);
            mem[1] = (byte) image.address;
            System.arraycopy(packed, 0, mem, 2, packed.length);
            return mem;
        } catch (IllegalArgumentException error) {
            engine.error("KABUTO: " + error.getMessage());
            return new byte[0];
        } catch (OutOfMemoryError error) {
            engine.error("KABUTO: insufficient Java heap; launch KickAssembler with -Xmx1g");
            return new byte[0];
        }
    }

    private static boolean booleanArgument(IValue[] values, int index, boolean fallback, String name) {
        if (values.length <= index) {
            return fallback;
        }
        if (!values[index].hasBooleanRepresentation()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return values[index].getBoolean();
    }

    static final class Image {
        final int address;
        final byte[] bytes;
        Image(int address, byte[] bytes) {
            this.address = address;
            this.bytes = bytes;
        }
    }

    static Image merge(List<IMemoryBlock> blocks) {
        List<IMemoryBlock> sorted = new ArrayList<>();
        for (IMemoryBlock block : blocks) {
            int start = block.getStartAddress();
            if (start < 0 || start > 65535 || (long) start + block.getBytes().length > 65536) {
                throw new IllegalArgumentException("memory block is outside $0000..$ffff: " + block.getName());
            }
            if (block.getBytes().length != 0) {
                sorted.add(block);
            }
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("no data to compress");
        }
        sorted.sort(Comparator.comparingInt(IMemoryBlock::getStartAddress));
        int start = sorted.get(0).getStartAddress();
        int end = start;
        for (IMemoryBlock block : sorted) {
            if (block.getStartAddress() < end) {
                throw new IllegalArgumentException("overlapping memory blocks: " + block.getName());
            }
            end = block.getStartAddress() + block.getBytes().length;
        }
        if (end - start > Encoder.MAX_INPUT) {
            throw new IllegalArgumentException("merged payload including gaps must contain 1..65535 bytes");
        }
        byte[] data = new byte[end - start];
        for (IMemoryBlock block : sorted) {
            System.arraycopy(block.getBytes(), 0, data, block.getStartAddress() - start, block.getBytes().length);
        }
        return new Image(start, data);
    }
}
