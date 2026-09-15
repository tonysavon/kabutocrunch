#!/usr/bin/env python3
"""Execute every SFX variant in VICE and compare its final RAM output."""
import argparse
import pathlib
import random
import subprocess
import sys
import tempfile
from benchmark_cycles import ROOT, build_encoder, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vice', required=True, type=pathlib.Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='kc-vice-') as directory:
        temp = pathlib.Path(directory)
        encoder = build_encoder(temp)
        data = random.Random(6510).randbytes(1024) + bytes(range(256))*180 + b'X'*5000
        destination = 0x1000
        original = temp / 'input.prg'
        original.write_bytes(destination.to_bytes(2, 'little') + data)
        for small in (False, True):
            for effect in (False, True):
                for relocate in (False, True):
                    packed = temp / 'sfx.prg'
                    output = temp / 'output.bin'
                    if output.exists():
                        output.unlink()
                    flags = (['--small'] if small else []) + (['--effect'] if effect else [])
                    if relocate:
                        flags += ['--relocate-sfx', '8192', '--01', '55', '--cli']
                    run([str(encoder), '--sfx', '1024', *flags, '-o', str(packed), str(original)])
                    monitor = temp / 'test.mon'
                    monitor.write_text(
                        f'load "{packed.as_posix()}" 0\n'
                        'break $0400\n'
                        f'g ${8192 if relocate else 2061:04x}\n'
                        'bank ram\n'
                        f'bsave "{output.as_posix()}" 0 ${destination:04x} ${destination+len(data)-1:04x}\n'
                        'quit\n')
                    command = [str(args.vice.resolve()), '-default', '-console', '-nativemonitor',
                               '-warp', '+sound', '-limitcycles', '20000000',
                               '-initbreak', 'ready', '-moncommands', str(monitor),
                               '-monlog', '-monlogname', str(temp / 'monitor.log')]
                    options = {}
                    if sys.platform == 'win32':
                        startup = subprocess.STARTUPINFO()
                        startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
                        startup.wShowWindow = 0
                        options = dict(startupinfo=startup, creationflags=subprocess.CREATE_NO_WINDOW)
                    result = subprocess.run(command, capture_output=True, timeout=45, cwd=temp, **options)
                    if result.returncode or not output.is_file() or output.read_bytes() != data:
                        log = temp / 'monitor.log'
                        raise RuntimeError(f'VICE failed ({result.returncode}): small={small}, effect={effect}, relocate={relocate}\n'
                                           + (log.read_text(errors='replace')[-6000:] if log.exists() else '')
                                           + result.stdout.decode(errors='replace')[-6000:]
                                           + result.stderr.decode(errors='replace')[-3000:])
                    print(f'VICE OK: small={small}, effect={effect}, relocate={relocate}', flush=True)
        print('PASS: 8 VICE SFX executions')


if __name__ == '__main__':
    main()
