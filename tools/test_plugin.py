#!/usr/bin/env python3
"""Compare plugin streams to C and execute assembled raw/Mem examples on a 6502."""
import argparse
import os
import pathlib
import random
import re
import subprocess
import sys
import tempfile
import zipfile

from benchmark_cycles import ROOT, KICKASS, MPU, install_undocumented, build_encoder
from test_release import C64Memory


def run(command):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kickass-jar', type=pathlib.Path, default=KICKASS)
    args = parser.parse_args()
    kickass = args.kickass_jar.resolve()
    source_checkout = (ROOT / 'plugin/src/main/java').is_dir()
    if source_checkout:
        from build_plugin import build
        jar = build(kickass)
    else:
        jar = ROOT / 'build/kabutocrunch-kickass.jar'
        if not jar.is_file():
            raise RuntimeError('Packaged plugin JAR is missing')
    with zipfile.ZipFile(jar) as archive:
        assert not any(name.endswith(('Decoder.class', 'SelfTest.class', 'PluginTest.class', 'Kabutocrunch.class'))
                       for name in archive.namelist()), 'test/CLI classes in production JAR'
    with tempfile.TemporaryDirectory(prefix='kabuto-plugin-test-') as directory:
        temp = pathlib.Path(directory)
        encoder = build_encoder(temp)
        if source_checkout:
            classes = temp / 'classes'
            run(['javac', '--release', '11', '-encoding', 'UTF-8', '-cp', os.pathsep.join(map(str, (kickass, jar))),
                 '-d', classes, *sorted((ROOT / 'plugin/src/test/java').rglob('*.java'))])
            rng = random.Random(921)
            random64k = rng.randbytes(65536)
            cases = [('one', b'x', (0, 2, 15)),
                     ('boundaries', b''.join(rng.randbytes(n) * 3 for n in (127, 128, 255, 256, 1023, 1024)), range(16)),
                     ('sparse', b'ABCD' * 128 + bytes(0x3000 - 512) + bytes(range(256)) * 2, (0, 2, 15)),
                     ('rle-max', bytes(65535), (0, 2, 15)),
                     ('random-max', random64k[:-1], (0, 2, 15)),
                     ('long-offset', random64k[:32640] + random64k[:1024], (0, 2, 15))]
            configurations = []
            for name, data, speeds in cases:
                source = temp / (name + '.bin')
                source.write_bytes(data)
                for dali in (False, True):
                    for speed in speeds:
                        packed = temp / f'{name}-{dali}-{speed}.lz'
                        configurations.append((source, packed, dali, speed))
            manifest = temp / 'cases.tsv'
            manifest.write_text('\n'.join(f'{source}\t{packed}\t{str(dali).lower()}\t{speed}'
                                          for source, packed, dali, speed in configurations))
            print(run(['java', '-Xmx1g', '-cp', os.pathsep.join(map(str, (classes, jar, kickass))),
                       'kabutocrunch.PluginTest', manifest]), end='', flush=True)
            for source, packed, dali, speed in configurations:
                flags = ['--dali'] if dali else []
                reference = temp / 'reference.lz'
                run([encoder, '--binfile', '--no-inplace', '--speed', speed, *flags, '-o', reference, source])
                assert reference.read_bytes() == packed.read_bytes(), (source.name, dali, speed)
                run([encoder, '--binfile', *flags, '--verify-packed', packed, source])
            print(f'PASS: {len(configurations)} Java/C byte comparisons and C decoder round trips', flush=True)

        launcher = ['java', '-Xmx1g', '-cp', os.pathsep.join(map(str, (kickass, jar))), 'kickass.KickAssembler']

        def assemble(name, text):
            source = temp / (name + '.asm')
            source.write_text(text)
            output = temp / (name + '.prg')
            run([*launcher, source, '-o', output])
            symbols = {name: int(value, 16) for name, value in re.findall(
                r'\.label (\w+)=\$([0-9a-f]+)', source.with_suffix('.sym').read_text())}
            return output.read_bytes(), symbols

        expected = bytes([0xa9, 6, 0x8d, 0x20, 0xd0, 0x60]) + bytes(range(250))
        expected += bytes(0x3000 - len(expected)) + bytes((i * 37) & 255 for i in range(512))
        execution_checks = 0
        for name in ('raw', 'mem', 'raw_dali', 'mem_dali'):
            text = (ROOT / 'examples/plugin' / (name + '.asm')).read_text()
            text = text.replace('../../src/asm/', (ROOT / 'src/asm').as_posix() + '/')
            # Execute both ascending and descending region order for every mode.
            for reverse in (False, True):
                variant = text
                if reverse:
                    first = variant.index('    .pc = $1000')
                    second = variant.index('    .pc = $4000')
                    end = variant.index('\n}', second)
                    variant = variant[:first] + variant[second:end] + '\n' + variant[first:second] + variant[end:]
                prg, symbols = assemble(name + ('-reverse' if reverse else ''), variant)
                memory = C64Memory()
                load = int.from_bytes(prg[:2], 'little')
                memory.ram[load:load + len(prg) - 2] = prg[2:]
                # Detect missing gap writes rather than relying on a zeroed emulator.
                memory.ram[0x1000:0x4200] = b'\xa5' * len(expected)
                cpu = MPU(memory=memory, pc=symbols['start'])
                install_undocumented(cpu)
                for _ in range(2_000_000):
                    if cpu.pc == symbols['done']:
                        break
                    cpu.step()
                else:
                    raise AssertionError(name + ': decoder did not return')
                assert memory.ram[0x1000:0x4200] == expected, name
                assert memory.io[0x20] == 6, 'decompressed code did not execute'
                assert symbols['first'] == 0x1000 and symbols['second'] == 0x4000
                stream = prg[2 + symbols['packed'] - load:2 + symbols['packedEnd'] - load]
                mem = name.startswith('mem')
                if mem:
                    assert stream[:2] == b'\x10\x00', 'Mem header byte order'
                    stream = stream[2:]
                payload = temp / 'example.bin'
                payload.write_bytes(expected)
                packed = temp / 'example.lz'
                packed.write_bytes(stream)
                run([encoder, '--binfile', *(['--dali'] if 'dali' in name else []),
                     '--verify-packed', packed, payload])
                execution_checks += 1
        print(f'PASS: {execution_checks} assembled raw/Mem/native/Dali examples, including gap writes and code execution', flush=True)

        # Several invocations in one assembler process with alternating formats/settings.
        text = '.plugin "kabutocrunch.KABUTO"\n*=$8000\n'
        variants = ((True, False, 0), (False, True, 15), (True, False, 2), (False, False, 15))
        for i, (raw, dali, speed) in enumerate(variants):
            text += f'p{i}:\n.modify KABUTO({str(raw).lower()}, {str(dali).lower()}, {speed}) {{\n'
            text += '.pc=$1000\n.fill 1024, i & $ff\n}\n'
        text += 'last:\n'
        prg, symbols = assemble('repeated', text)
        data = bytes(range(256)) * 4
        source = temp / 'repeated.bin'
        source.write_bytes(data)
        for i, (raw, dali, speed) in enumerate(variants):
            end = symbols[f'p{i + 1}'] if i < 3 else symbols['last']
            stream = prg[2 + symbols[f'p{i}'] - 0x8000:2 + end - 0x8000]
            reference = temp / 'repeated.lz'
            run([encoder, '--binfile', '--no-inplace', '--speed', speed,
                 *(['--dali'] if dali else []), '-o', reference, source])
            assert stream == (b'' if raw else b'\x10\x00') + reference.read_bytes()
        print('PASS: 4 modifiers in one assembly with independent settings', flush=True)

        invalid = [
            ('false, false, -1', '.byte 1', 'speed must'),
            ('false, false, 16', '.byte 1', 'speed must'),
            ('false, false, 1.5', '.byte 1', 'speed must'),
            ('"raw"', '.byte 1', 'raw must'),
            ('true, "dali"', '.byte 1', 'dali must'),
            ('true, false, 2, 3', '.byte 1', 'at most 3'),
            ('', '', 'no data'),
            ('', '.pc=$1000\n.byte 1,2\n.pc=$1001\n.byte 3', 'overlap'),
            ('', '.pc=$ffff\n.byte 1,2', 'outside'),
            ('', '.pc=$0000\n.byte 1\n.pc=$ffff\n.byte 2', '1..65535'),
        ]
        for i, (values, body, message) in enumerate(invalid):
            source = temp / f'invalid-{i}.asm'
            source.write_text(f'.plugin "kabutocrunch.KABUTO"\n*=$8000\n.modify KABUTO({values}) {{\n{body}\n}}\n')
            result = subprocess.run([*launcher, str(source)], capture_output=True, text=True)
            assert result.returncode != 0 and message in result.stdout + result.stderr, result.stdout + result.stderr
        print(f'PASS: {len(invalid)} invalid assembler inputs rejected', flush=True)


if __name__ == '__main__':
    main()
