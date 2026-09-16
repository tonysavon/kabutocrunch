#!/usr/bin/env python3
"""Package the Windows executable, plugin, ASM, documentation and binary tests."""
import argparse
import hashlib
import pathlib
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def archive(path, entries):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as output:
        for name, data in sorted(entries.items()):
            item = zipfile.ZipInfo('kabutocrunch/' + name, (2026, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            item.external_attr = 0o644 << 16
            output.writestr(item, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='1.0')
    args = parser.parse_args()
    if not args.version or any(c not in '0123456789.-' for c in args.version):
        raise SystemExit('Version must contain only digits, dots and hyphens')
    executable = ROOT / 'kcrunch.exe'
    plugin = ROOT / 'build/kabutocrunch-kickass.jar'
    if not executable.is_file() or not plugin.is_file():
        raise SystemExit('Build kcrunch.exe and the plugin JAR before packaging')
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    names = [name for name in names if name]
    source = {name: (ROOT / name).read_bytes() for name in names}
    test_files = ('tools/benchmark_cycles.py', 'tools/test_release.py',
                  'tools/test_examples.py', 'tools/test_plugin.py', 'tools/smoke_vice.py')
    binary = {name: data for name, data in source.items()
              if name in ('README.md', 'RELEASE.md', 'LICENSE', 'plugin/README.md',
                          'requirements-test.txt', 'docs/performance.md', *test_files)
              or name.startswith(('licenses/', 'src/asm/', 'examples/'))}
    binary['kcrunch.exe'] = executable.read_bytes()
    binary['build/kabutocrunch-kickass.jar'] = plugin.read_bytes()
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    assets = [dist / f'kabutocrunch-{args.version}-windows-x64.zip']
    archive(assets[0], binary)
    (dist / 'SHA256SUMS.txt').write_text(''.join(
        hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n' for path in assets))
    print(f'Packaged {len(binary)} files without C/Java sources: {assets[0]}')


if __name__ == '__main__':
    main()
