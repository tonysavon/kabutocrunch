#!/usr/bin/env python3
"""Compare parser search budgets without changing the production encoder."""
import argparse
import json
import pathlib
import re
import tempfile
import time
from benchmark_cycles import ROOT, DEFAULT_CASES, KCRUNCH_SOURCES, run

PROFILES = {
    'baseline': '109, 40, 78, 9, 340, 1, 1280, 512, 16, 8, 130, 10, 3, 20',
    'lean': '64, 24, 48, 9, 340, 1, 1280, 512, 16, 8, 130, 10, 3, 20',
    'wide': '160, 60, 100, 12, 512, 0, 1280, 512, 24, 8, 256, 16, 4, 20',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    results = []
    source = (ROOT / 'src/kc_search.c').read_text()
    with tempfile.TemporaryDirectory(prefix='kc-search-audit-') as directory:
        temp = pathlib.Path(directory)
        for profile, values in PROFILES.items():
            modified, count = re.subn(r'"baseline", [\d, ]+', '"' + profile + '", ' + values, source)
            assert count == 1
            search = temp / 'kc_search.c'
            search.write_text(modified)
            encoder = temp / 'audit.exe'
            sources = [str(search) if s == 'src/kc_search.c' else s for s in KCRUNCH_SOURCES]
            run(['gcc', '-O3', '-std=c99', '-Wall', '-Wextra', '-Werror',
                 '-fomit-frame-pointer', '-Isrc', '-Isrc/libdivsufsort/include',
                 *sources, '-o', str(encoder)])
            for name, path in DEFAULT_CASES:
                if not path.is_file():
                    continue
                raw = temp / 'input.bin'
                raw.write_bytes(path.read_bytes()[2:])
                packed = temp / 'packed.bin'
                start = time.perf_counter()
                run([str(encoder), '--binfile', '-dali', '--speed', '0',
                     '-o', str(packed), str(raw)])
                seconds = time.perf_counter() - start
                run([str(encoder), '--binfile', '-dali', '--verify-packed', str(packed), str(raw)])
                row = dict(profile=profile, game=name, packed=len(packed.read_bytes()),
                           seconds=round(seconds, 3))
                results.append(row)
                print(row, flush=True)
    args.output.write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
