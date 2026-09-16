#!/usr/bin/env python3
"""Measure the selected Kabuto or Dali decruncher in cycles per output byte."""

from __future__ import annotations

import argparse
import os
import pathlib
import subprocess
import sys
import tempfile

try:
    from py65.devices.mpu6502 import MPU
except ImportError as error:
    raise SystemExit("py65 is required: python -m pip install py65") from error


ROOT = pathlib.Path(__file__).resolve().parents[1]
PARENT = ROOT.parent
ENTRY = 0x0200
STOP = 0x020F
ENTRY_STUB_SIZE = STOP - ENTRY + 1
MAX_INSTRUCTIONS = 20_000_000
KICKASS = pathlib.Path(os.environ.get("KICKASS_JAR", "KickAss.jar"))
DEFAULT_CASES = (
    ("kabutocrunch", ROOT / "game.prg"),
    ("crackpots", PARENT / "crackpots_working_folder/src/game.prg"),
    ("abbey", PARENT / "abbey/src/abbey.prg"),
    ("fixit", PARENT / "fixitfelixjr/src/game.prg"),
    ("pigquest", PARENT / "pigquest/src/game.prg"),
    ("keystone", PARENT / "KeystoneKapers/src/game.prg"),
)
KCRUNCH_SOURCES = (
    "src/kcrunch.c", "src/kc_format.c", "src/kc_search.c", "src/kc_shrink.c",
    "src/kc_matchfinder.c", "src/decode.c",
    "src/libdivsufsort/lib/divsufsort.c",
    "src/libdivsufsort/lib/divsufsort_utils.c",
    "src/libdivsufsort/lib/sssort.c", "src/libdivsufsort/lib/trsort.c",
)


class BenchmarkError(RuntimeError):
    pass


class CheckedMemory:
    def __init__(self, destination: int, expected: bytes) -> None:
        self.data = bytearray(65536)
        self.destination = destination
        self.expected = expected
        self.running = False
        self.cpu: MPU | None = None

    def __len__(self) -> int:
        return 65536

    def __getitem__(self, address):
        return self.data[address]

    def __setitem__(self, address, value) -> None:
        if isinstance(address, slice):
            self.data[address] = value
            return
        address &= 0xffff
        if self.running and self.destination <= address < self.destination + len(self.expected):
            index = address - self.destination
            if value != self.expected[index]:
                pc = self.cpu.pc if self.cpu else 0
                src = self.data[0xFB] | (self.data[0xFC] << 8)
                dst = self.data[0xF9] | (self.data[0xFA] << 8)
                registers = (
                    f", src=${src:04x}, dst=${dst:04x}, "
                    f"A=${self.cpu.a:02x}, X=${self.cpu.x:02x}, Y=${self.cpu.y:02x}"
                    if self.cpu else ""
                )
                raise BenchmarkError(
                    f"wrong output +${index:04x} at PC ${pc:04x}: "
                    f"got ${value:02x}, expected ${self.expected[index]:02x}"
                    f"{registers}"
                )
        self.data[address] = value & 0xff


def run(command: list[str], cwd: pathlib.Path = ROOT) -> None:
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=True)
    if result.returncode:
        raise BenchmarkError(
            f"command failed ({result.returncode}): {' '.join(command)}\n"
            f"{result.stdout}{result.stderr}"
        )


def build_encoder(directory: pathlib.Path) -> pathlib.Path:
    # Binary release ZIPs deliberately omit compiler sources.
    if not (ROOT / "src/kcrunch.c").is_file():
        prebuilt = ROOT / ("kcrunch.exe" if sys.platform == "win32" else "kcrunch")
        if not prebuilt.is_file():
            raise BenchmarkError("This package needs its prebuilt kcrunch executable")
        return prebuilt
    executable = directory / ("kcrunch-cycle.exe" if sys.platform == "win32" else "kcrunch-cycle")
    run([
        "gcc", "-O3", "-std=c99", "-Wall", "-Wextra", "-Werror",
        "-fomit-frame-pointer", "-Isrc", "-Isrc/libdivsufsort/include",
        *KCRUNCH_SOURCES, "-o", str(executable),
    ])
    return executable


def assemble_decoder(jar: pathlib.Path, directory: pathlib.Path,
                     dali: bool = False) -> bytes:
    source = directory / "cycle_decoder.asm"
    output = directory / "cycle_decoder.prg"
    prefix = "ZX0" if dali else "KABUTO"
    namespace = "zx0" if dali else "kabuto"
    decoder_name = "dcrunch_dali.asm" if dali else "dcrunch.asm"
    decoder = (ROOT / "src/asm" / decoder_name).resolve().as_posix()
    source.write_text(
        "* = $0200 \"cycle benchmark entry\"\n"
        "        ldy #$00\n        ldx #$00\n"
        "        lda #$00\n        sta.zp lz_dst\n"
        "        lda #$00\n        sta.zp lz_dst + 1\n"
        f"        jsr {namespace}.rawdecrunch\n        brk\n"
        f"#define {prefix}RAW\n#define {prefix}RAW_FAST\n"
        f"#import \"{decoder}\"\n",
        encoding="ascii",
    )
    run(["java", "-jar", str(jar), str(source), "-o", str(output)])
    image = output.read_bytes()
    if len(image) < 3 or int.from_bytes(image[:2], "little") != ENTRY:
        raise BenchmarkError(f"unexpected decoder image: {output}")
    return image[2:]


def install_undocumented(cpu: MPU) -> None:
    def alr(mpu: MPU) -> None:
        mpu.a &= mpu.ByteAt(mpu.pc)
        mpu.pc += 1
        if mpu.a & 1:
            mpu.p |= mpu.CARRY
        else:
            mpu.p &= ~mpu.CARRY
        mpu.a >>= 1
        mpu.FlagsNZ(mpu.a)

    def anc(mpu: MPU) -> None:
        mpu.a &= mpu.ByteAt(mpu.pc)
        mpu.pc += 1
        if mpu.a & 0x80:
            mpu.p |= mpu.CARRY
        else:
            mpu.p &= ~mpu.CARRY
        mpu.FlagsNZ(mpu.a)

    def lax_zp(mpu: MPU) -> None:
        value = mpu.ByteAt(mpu.ByteAt(mpu.pc))
        mpu.pc += 1
        mpu.a = value
        mpu.x = value
        mpu.FlagsNZ(value)

    def lax_indirect_y(mpu: MPU) -> None:
        value = mpu.ByteAt(mpu.IndirectYAddr())
        mpu.pc += 1
        mpu.a = value
        mpu.x = value
        mpu.FlagsNZ(value)

    for opcode, handler, cycles, mode in (
        (0x0B, anc, 2, ("ANC", "imm")),
        (0x2B, anc, 2, ("ANC", "imm")),
        (0x4B, alr, 2, ("ALR", "imm")),
        (0xA7, lax_zp, 3, ("LAX", "zpg")),
        (0xB3, lax_indirect_y, 5, ("LAX", "iny")),
    ):
        cpu.instruct[opcode] = handler
        cpu.cycletime[opcode] = cycles
        cpu.extracycles[opcode] = 0
        cpu.disassemble[opcode] = mode


def count_cycles(payload: bytes, stream: bytes, expected: bytes, destination: int) -> int:
    source = 0xfff0 - len(stream)
    if source <= ENTRY + len(payload):
        raise BenchmarkError("packed stream overlaps decoder")
    if destination + len(expected) > 0x10000:
        raise BenchmarkError("decompressed output wraps past 64 KiB")

    memory = CheckedMemory(destination, expected)
    memory.data[ENTRY:ENTRY + len(payload)] = payload
    memory.data[source:source + len(stream)] = stream
    for offset, value in zip((1, 3, 5, 9),
                             (source & 0xff, source >> 8,
                              destination & 0xff, destination >> 8)):
        memory.data[ENTRY + offset] = value

    cpu = MPU(memory=memory, pc=ENTRY)
    install_undocumented(cpu)
    memory.cpu = cpu
    memory.running = True
    instructions = 0
    while cpu.pc != STOP:
        if cpu.disassemble[memory[cpu.pc]][0] == "???":
            raise BenchmarkError(f"unsupported opcode at ${cpu.pc:04x}")
        cpu.step()
        instructions += 1
        if instructions > MAX_INSTRUCTIONS:
            dst = memory.data[0xF9] | (memory.data[0xFA] << 8)
            src = memory.data[0xFB] | (memory.data[0xFC] << 8)
            raise BenchmarkError(
                f"decoder did not terminate (PC=${cpu.pc:04x}, "
                f"src=${src:04x}, dst=${dst:04x}, bits=${memory.data[0xF8]:02x}, "
                f"A=${cpu.a:02x}, X=${cpu.x:02x}, Y=${cpu.y:02x})"
            )
    memory.running = False
    actual = bytes(memory.data[destination:destination + len(expected)])
    if actual != expected:
        raise BenchmarkError("final decompressed data differs from input")
    if isinstance(payload, bytearray):
        payload[:] = memory.data[ENTRY:ENTRY + len(payload)]
    return cpu.processorCycles


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prg", nargs="*", type=pathlib.Path,
                        help="PRG files to benchmark; defaults to the usual local corpus")
    parser.add_argument("--kickass-jar", type=pathlib.Path, default=KICKASS)
    parser.add_argument("--speed", type=int,
                        help="encoder --speed bias; defaults to the encoder default")
    parser.add_argument("--dali", action="store_true",
                        help="benchmark classic Dali format and its fast decoder")
    args = parser.parse_args()
    if not args.kickass_jar.is_file():
        raise BenchmarkError(f"KickAssembler not found: {args.kickass_jar}")

    cases = ((path.stem, path.resolve()) for path in args.prg) if args.prg else (
        (name, path) for name, path in DEFAULT_CASES if path.is_file()
    )
    cases = tuple(cases)
    if not cases:
        raise BenchmarkError("no PRG files found")

    totals = [0, 0, 0]
    with tempfile.TemporaryDirectory(prefix="kabutocrunch-cycles-") as temp_name:
        temp = pathlib.Path(temp_name)
        encoder = build_encoder(temp)
        payload = assemble_decoder(args.kickass_jar, temp, args.dali)
        print(f"decoder size: {len(payload) - ENTRY_STUB_SIZE} bytes")
        print("game          packed  output      cycles  cycles/byte")
        for name, path in cases:
            prg = path.read_bytes()
            if len(prg) < 3:
                raise BenchmarkError(f"invalid PRG: {path}")
            destination = int.from_bytes(prg[:2], "little")
            expected = prg[2:]
            raw = temp / f"{name}.bin"
            packed = temp / f"{name}.lz"
            raw.write_bytes(expected)
            command = [str(encoder), "--binfile", "--no-inplace"]
            if args.dali:
                command.append("-dali")
            if args.speed is not None:
                command += ["--speed", str(args.speed)]
            command += ["-o", str(packed), str(raw)]
            run(command)
            stream = packed.read_bytes()
            cycles = count_cycles(payload, stream, expected, destination)
            totals[0] += len(stream)
            totals[1] += len(expected)
            totals[2] += cycles
            print(f"{name:12s} {len(stream):6d} {len(expected):7d} {cycles:11d} {cycles / len(expected):12.6f}")

    print(f"{'TOTAL':12s} {totals[0]:6d} {totals[1]:7d} {totals[2]:11d} {totals[2] / totals[1]:12.6f}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (BenchmarkError, OSError) as error:
        raise SystemExit(f"error: {error}") from error
