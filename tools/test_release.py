#!/usr/bin/env python3
"""Portable deterministic round trips and full 6510 SFX execution tests."""
import argparse
import pathlib
import random
import subprocess
import tempfile

from benchmark_cycles import (ROOT, KICKASS, DEFAULT_CASES, MPU, BenchmarkError,
                              build_encoder, run, assemble_decoder, count_cycles,
                              install_undocumented, MAX_INSTRUCTIONS)


class C64Memory:
    """RAM under ROM/I/O, sufficient for the original Dali loader and effect."""
    def __init__(self):
        self.ram = bytearray((i * 73 + 19) & 255 for i in range(65536))
        self.ram[0:2] = bytes([0x2f, 0x37])
        self.io = bytearray(4096)
        self.effect_writes = 0

    def __getitem__(self, address):
        address &= 65535
        port = self.ram[1]
        if 0xd000 <= address < 0xe000 and port & 3:
            return self.io[address - 0xd000] if port & 4 else 0xcc
        if (0xa000 <= address < 0xc000 and port & 3 == 3) or (address >= 0xe000 and port & 2):
            return 0xcc
        return self.ram[address]

    def __setitem__(self, address, value):
        address &= 65535
        if 0xd000 <= address < 0xe000 and self.ram[1] & 3 and self.ram[1] & 4:
            self.io[address - 0xd000] = value & 255
            if address == 0xd020:
                self.effect_writes += 1
        else:
            self.ram[address] = value & 255


def execute_sfx(image, expected, destination, entry, target, small=False,
                effect=False, port=0x37, cli=False):
    memory = C64Memory()
    load = int.from_bytes(image[:2], 'little')
    assert load + len(image) - 2 <= 65536
    memory.ram[load:load + len(image) - 2] = image[2:]
    original_zp = bytes(memory.ram[:256])
    cpu = MPU(memory=memory, pc=entry)
    install_undocumented(cpu)

    def lax_zpy(mpu):
        mpu.a = mpu.x = mpu.ByteAt((mpu.ByteAt(mpu.pc) + mpu.y) & 255)
        mpu.pc += 1
        mpu.FlagsNZ(mpu.a)

    def dcp_zp(mpu):
        address = mpu.ByteAt(mpu.pc)
        mpu.pc += 1
        value = (mpu.ByteAt(address) - 1) & 255
        mpu.memory[address] = value
        mpu.p &= ~mpu.CARRY
        if mpu.a >= value:
            mpu.p |= mpu.CARRY
        mpu.FlagsNZ((mpu.a - value) & 255)

    for opcode, handler, cycles in ((0xb7, lax_zpy, 4), (0xc7, dcp_zp, 5)):
        cpu.instruct[opcode] = handler
        cpu.cycletime[opcode] = cycles
        cpu.extracycles[opcode] = 0
        cpu.disassemble[opcode] = ('SFX', 'zpg')
    for _ in range(MAX_INSTRUCTIONS):
        if cpu.pc == target:
            break
        if cpu.disassemble[memory[cpu.pc]][0] == '???' or memory[cpu.pc] == 0:
            raise BenchmarkError(f'SFX invalid opcode at ${cpu.pc:04x}')
        cpu.step()
    else:
        raise BenchmarkError(f'SFX did not terminate: PC=${cpu.pc:04x}')
    actual = bytes(memory.ram[destination:destination + len(expected)])
    if actual != expected:
        index = next(i for i, (a, b) in enumerate(zip(actual, expected)) if a != b)
        raise BenchmarkError(f'SFX output mismatch at ${destination+index:04x}')
    assert memory.ram[0] == original_zp[0], 'processor DDR corrupted'
    assert memory.ram[1] == (0x34 if small else port), '$01 not restored'
    assert bool(cpu.p & cpu.INTERRUPT) == (small or not cli), 'interrupt flag'
    if not small:
        restored_end = 233 if effect else 226
        assert memory.ram[2:restored_end] == original_zp[2:restored_end], 'zero page restoration'
        assert cpu.sp == 255, 'stack restoration'
    if effect and len(expected) > 512 and expected == expected[:1] * len(expected):
        assert memory.effect_writes, 'missing border effect'
    return cpu.processorCycles


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kickass-jar', type=pathlib.Path, default=KICKASS)
    parser.add_argument('--corpus', action='store_true', help='also test available local games')
    args = parser.parse_args()
    rng = random.Random(640)
    cases = [('one', b'Q'), ('two', b'QQ')]
    for size in (7, 8, 127, 128, 255, 256, 257, 511, 512, 513, 4096):
        cases += [(f'rle{size}', b'Z' * size),
                  (f'random{size}', rng.randbytes(size))]
    cases += [('pages', bytes(range(256)) * 40),
              ('mixed', rng.randbytes(600) + b'abcd' * 2000 + rng.randbytes(257)),
              ('long', b'a' * 20000 + b'b' * 17000 + b'abc' * 4000)]
    distant = rng.randbytes(32640)
    cases.append(('long-offset', distant + distant[:1024]))
    with tempfile.TemporaryDirectory(prefix='kc-release-') as directory:
        temp = pathlib.Path(directory)
        encoder = build_encoder(temp)
        decoders = [assemble_decoder(args.kickass_jar, temp, dali) for dali in (False, True)]
        checks = 0
        # Keep self-modified code between calls: loaders commonly reuse it.
        for dali in (False, True):
            reusable = bytearray(decoders[dali])
            for data in (b'abcdef' * 100, b'Z' * 256):
                raw = temp / 'reuse.bin'
                packed = temp / 'reuse.lz'
                raw.write_bytes(data)
                run([str(encoder), '--binfile', *(['-dali'] if dali else []),
                     '-o', str(packed), str(raw)])
                count_cycles(reusable, packed.read_bytes(), data, 0x1000)
                checks += 1
        for name, data in cases:
            raw = temp / 'input.bin'
            raw.write_bytes(data)
            for dali in (False, True):
                for speed in (0, 2, 7):
                    flags = ['-dali'] if dali else []
                    packed = temp / 'raw.lz'
                    run([str(encoder), '--binfile', '--no-inplace', '--speed', str(speed),
                         *flags, '-o', str(packed), str(raw)])
                    run([str(encoder), '--binfile', *flags, '--verify-packed', str(packed), str(raw)])
                    count_cycles(decoders[dali], packed.read_bytes(), data, 0x1000)
                    checks += 1
            if name in ('mixed', 'long', 'pages'):
                for dali in (False, True):
                    packed = temp / 'short.lz'
                    flags = ['-dali'] if dali else []
                    run([str(encoder), '--binfile', '--short', '--speed', '15',
                         *flags, '-o', str(packed), str(raw)])
                    count_cycles(decoders[dali], packed.read_bytes(), data, 0x1000)
                    checks += 1
            print(f'raw OK: {name}', flush=True)
        # Host-side format limits, not full-memory 6502 execution: the decoder,
        # stack and packed source must still live somewhere on a real C64.
        boundary = random.Random(921).randbytes(65536)
        for dali in (False, True):
            flags = ['--dali'] if dali else []
            raw = temp / 'boundary.bin'
            raw.write_bytes(boundary[:-1])
            run([str(encoder), '--binfile', '--speed', '15', *flags, '--verify', str(raw)])
            checks += 1
            for cbm in (False, True):
                raw.write_bytes((b'\x00\x00' if cbm else b'') + boundary)
                for verify in (False, True):
                    packed = temp / 'boundary.lz'
                    packed.write_bytes(b'keep existing output')
                    result = subprocess.run([str(encoder), *flags, '--speed', '15',
                                             *([] if cbm else ['--binfile']),
                                             *(['--verify'] if verify else ['--no-inplace']),
                                             '-o', str(packed), str(raw)], capture_output=True)
                    assert result.returncode != 0 and b'1..65535' in result.stderr
                    assert packed.read_bytes() == b'keep existing output'
                    checks += 1
        print('payload limits OK', flush=True)
        sfx_cases = [(name, 0x1000, data) for name, data in cases]
        sfx_cases += [('under-rom-io', 0x9fff, bytes(range(256)) * 80),
                      ('near-top', 0xff00, b'T' * 240),
                      ('low-ram', 0x0200, b'L' * 256)]
        if args.corpus:
            for name, path in DEFAULT_CASES:
                if path.is_file():
                    prg = path.read_bytes()
                    sfx_cases.append((name, int.from_bytes(prg[:2], 'little'), prg[2:]))
        for name, destination, data in sfx_cases:
            prg = temp / 'input.prg'
            prg.write_bytes(destination.to_bytes(2, 'little') + data)
            for small in (False, True):
                for effect in (False, True):
                    for relocate in (False, True):
                        packed = temp / 'sfx.prg'
                        flags = (['--small'] if small else []) + (['--effect'] if effect else [])
                        if relocate:
                            flags += ['--relocate-sfx', '8192', '--01', '53', '--cli']
                        run([str(encoder), '--sfx', '1024', *flags, '-o', str(packed), str(prg)])
                        execute_sfx(packed.read_bytes(), data, destination,
                                    8192 if relocate else 2061, 1024, small, effect,
                                    53 if relocate else 55, relocate)
                        checks += 1
            print(f'SFX OK: {name}', flush=True)
        for speed in (0, 7, 15):
            data = b'xyz' * 1000 + rng.randbytes(512)
            prg = temp / 'speed.prg'
            prg.write_bytes(b'\x00\x10' + data)
            packed = temp / 'speed-sfx.prg'
            run([str(encoder), '--sfx', '1024', '--speed', str(speed),
                 '-o', str(packed), str(prg)])
            execute_sfx(packed.read_bytes(), data, 0x1000, 2061, 1024)
            checks += 1
        for name, destination, data, flags in (
            ('empty', 0x1000, b'', []),
            ('zp', 0x00f0, b'x'*300, []),
            ('stack', 0x0100, b'x'*300, []),
            ('wrap', 0xff00, b'x'*300, []),
            ('unread-eod', 0xff00, b'x'*256, []),
            ('oversize', 0x0801, b'x'*65537, []),
            ('bad-entry', 0x1000, b'x', ['--sfx', '65536']),
            ('bad-port', 0x1000, b'x', ['--01', '256']),
            ('rom-loader', 0x1000, b'x', ['--relocate-sfx', '40960']),
            ('io-loader', 0x1000, b'x', ['--relocate-sfx', '53248']),
            ('wrap-loader', 0x1000, b'x'*300, ['--relocate-sfx', '65535']),
            ('verify-mode', 0x1000, b'x', ['--verify']),
            ('dictionary', 0x1000, b'x', ['--prefix-from', '4096']),
        ):
            prg = temp / 'invalid.prg'
            packed = temp / 'invalid-sfx.prg'
            packed.write_bytes(b'keep existing output')
            prg.write_bytes(destination.to_bytes(2, 'little') + data)
            result = subprocess.run([str(encoder), '--sfx', '1024', *flags,
                                     '-o', str(packed), str(prg)], capture_output=True)
            assert result.returncode != 0, f'accepted invalid SFX: {name}'
            assert packed.read_bytes() == b'keep existing output', f'clobbered output: {name}'
            checks += 1
        print(f'PASS: {checks} round-trip/execution checks', flush=True)


if __name__ == '__main__':
    main()
