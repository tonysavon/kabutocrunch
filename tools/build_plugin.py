#!/usr/bin/env python3
"""Build the Java 11 KickAssembler plugin; no Maven or native libraries needed."""
import argparse
import pathlib
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_JAR = ROOT / 'build/kabutocrunch-kickass.jar'


def build(kickass, output=DEFAULT_JAR):
    kickass = pathlib.Path(kickass).resolve()
    output = pathlib.Path(output).resolve()
    if not kickass.is_file():
        raise SystemExit(f'KickAssembler JAR not found: {kickass}')
    sources = sorted((ROOT / 'plugin/src/main/java').rglob('*.java'))
    with tempfile.TemporaryDirectory(prefix='kabuto-javac-') as directory:
        classes = pathlib.Path(directory)
        subprocess.run(['javac', '--release', '11', '-encoding', 'UTF-8', '-Xlint:all',
                        '-Werror', '-cp', str(kickass), '-d', str(classes),
                        *map(str, sources)], check=True)
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as jar:
            entries = {'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\nImplementation-Title: Kabutocrunch KickAssembler Plugin\r\n\r\n'}
            entries.update({p.relative_to(classes).as_posix(): p.read_bytes()
                            for p in sorted(classes.rglob('*.class'))})
            entries['LICENSE'] = (ROOT / 'LICENSE').read_bytes()
            for path in sorted((ROOT / 'licenses').glob('*')):
                entries['licenses/' + path.name] = path.read_bytes()
            for name, data in sorted(entries.items()):
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                jar.writestr(info, data)
    print(f'Built {output}', flush=True)
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kickass-jar', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, default=DEFAULT_JAR)
    args = parser.parse_args()
    build(args.kickass_jar, args.output)
