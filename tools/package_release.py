#!/usr/bin/env python3
"""Package the tested Windows executable and Java plugin with tracked source."""
import hashlib
import pathlib
import shutil
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
    executable = ROOT / 'kcrunch.exe'
    plugin = ROOT / 'build/kabutocrunch-kickass.jar'
    if not executable.is_file() or not plugin.is_file():
        raise SystemExit('Build kcrunch.exe and the plugin JAR before packaging')
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    names = [name for name in names if name]
    if 'plugin/src/main/java/kabutocrunch/KABUTO.java' not in names:
        raise SystemExit('Stage new release source files before packaging')
    source = {name: (ROOT / name).read_bytes() for name in names}
    binary = {name: data for name, data in source.items()
              if name in ('README.md', 'RELEASE.md', 'LICENSE', 'plugin/README.md')
              or name.startswith(('licenses/', 'src/asm/', 'examples/', 'docs/'))}
    binary['kcrunch.exe'] = executable.read_bytes()
    binary['build/kabutocrunch-kickass.jar'] = plugin.read_bytes()
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    archive(dist / 'kabutocrunch-source.zip', source)
    archive(dist / 'kabutocrunch-windows-x64.zip', binary)
    shutil.copyfile(plugin, dist / plugin.name)
    assets = [dist / name for name in ('kabutocrunch-source.zip', 'kabutocrunch-windows-x64.zip', plugin.name)]
    (dist / 'SHA256SUMS.txt').write_text(''.join(
        hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n' for path in assets))
    print(f'Packaged {len(source)} source files, Windows executable and plugin under {dist}')


if __name__ == '__main__':
    main()
