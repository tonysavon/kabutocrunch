"""Assemble and byte-check regular/in-place examples in both stream formats."""
import argparse
import pathlib
import re
import shutil
import sys
import tempfile
from benchmark_cycles import ROOT, KICKASS, MPU, run, install_undocumented
from test_release import C64Memory

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--kickass-jar', type=pathlib.Path, default=KICKASS)
args = parser.parse_args()
encoder = ROOT / ('kcrunch.exe' if sys.platform == 'win32' else 'kcrunch')
run([sys.executable, str(ROOT / 'examples/make_data.py')])
expected = (ROOT / 'examples/data.bin').read_bytes()
with tempfile.TemporaryDirectory(prefix='kc-examples-') as directory:
    temp = pathlib.Path(directory)
    for filename in ('data.bin', 'data.prg'):
        shutil.copyfile(ROOT / 'examples' / filename, temp / filename)
    for dali in (False, True):
        flags = ['-dali'] if dali else []
        run([str(encoder), *flags, '--binfile', '--no-inplace', '-o',
             str(temp / 'data.lz'), str(temp / 'data.bin')])
        run([str(encoder), *flags, '--inplace', '-o',
             str(temp / 'data-inplace.prg'), str(temp / 'data.prg')])
        decoder = ROOT / 'src/asm' / ('dcrunch_dali.asm' if dali else 'dcrunch.asm')
        for mode in ('regular', 'inplace'):
            source = temp / (mode + '.asm')
            source.write_text((ROOT / 'examples' / source.name).read_text().replace(
                '../src/asm/dcrunch.asm', decoder.as_posix()))
            image = temp / (mode + '.prg')
            run(['java', '-jar', str(args.kickass_jar.resolve()), str(source), '-o', str(image)])
            symbols = source.with_suffix('.sym').read_text()
            def address(label):
                return int(re.search(r'\.label ' + label + r'=\$([0-9a-f]+)', symbols)[1], 16)
            memory = C64Memory()
            data = image.read_bytes()
            load = int.from_bytes(data[:2], 'little')
            memory.ram[load:load + len(data) - 2] = data[2:]
            cpu = MPU(memory=memory, pc=address('start'))
            install_undocumented(cpu)
            done = address("done")
            for _ in range(2_000_000):
                if cpu.pc == done:
                    break
                cpu.step()
            else:
                raise RuntimeError(f'{mode}: did not return')
            assert memory.ram[0x4000:0x4000+len(expected)] == expected, mode
            print(f'PASS: {"Dali" if dali else "Kabuto"} {mode}', flush=True)
