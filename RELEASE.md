# Initial private release preparation

## Source repository contents

- `src/*.c`, `src/*.h`, `src/libdivsufsort/`: C99 encoder/decoder, required
  dependency sources, and generated SFX arrays.
- `src/asm/dcrunch.asm`, `dcrunch_dali.asm`: matching C64 decoders.
- `src/asm/sfx/`: original Dali SFX assembly, provenance, and size assertion.
- `Makefile`, `README.md`, `LICENSE`, `licenses/`: build, usage and licenses.
- `examples/`: runnable regular and in-place KickAssembler integration examples.
- `plugin/`: Java encoder and KickAssembler modifier, documentation, and tests.
  No Java command-line cruncher is included.
- `tools/`, `requirements-test.txt`: deterministic release tests, VICE smoke
  tests, SFX regeneration, and performance/search measurement tools.
- `docs/`, `benchmarks/search-audit.json`: performance results and audit evidence.

Game binaries, copyrighted test corpus, development history, stale SFX stubs,
IDE files, emulator/assembler binaries, and generated debug files are excluded.
Tests generate their own data. The optional local corpus is not distributed.
The original game-specific checksum harness is development-only and excluded.

## Windows binary package

`kcrunch.exe`, `build/kabutocrunch-kickass.jar`, README, plugin instructions,
LICENSE, all third-party license notices, both assembly decoders, runnable
examples, and Dali SFX source/provenance. The executable and plugin are built
from the same source snapshot. SHA-256 accompanies the archive. The plugin JAR
is also distributed as a separate asset for Linux/macOS and existing setups.
KickAssembler itself is not bundled.

## Validation

The preceding audit passed 490 checks including six local games. The updated
release passes 452 self-contained native/SFX round-trip and execution checks,
plus the four regular/in-place integration examples. GCC builds with
`-Wall -Wextra -Werror`. Historical corpus measurements remain in docs/.
All eight VICE SFX variant/relocation checks also pass, and all four SFX arrays
regenerate byte-for-byte from the original Dali-derived assembly source.

The plugin passes 62 Java/C byte comparisons with independent C verification,
62 Java test-decoder round trips, suffix-array/length-code self-tests, concurrent
encoder calls, eight assembled raw/Mem examples (both formats and region orders),
four modifiers in one assembly, and ten invalid assembler inputs. Each example
checks the full output including gaps and executes its decompressed code.

Payloads are limited to 1..65,535 bytes. A 65,536-byte payload could previously
produce an unrepresentable literal run at high speed settings. Both the C
encoder and plugin now reject that size before emitting output; this does not
increase decoder size. Host-side maximum-size tests are separate from 6502
execution tests, which reserve memory for the decoder, stack and packed source.

SFX automatically selects Dali. Use `$01=$37` with `--cli` for the standard
KERNAL IRQ handler; custom mappings require an appropriate visible handler.

`make test` includes native, SFX, integration-example and plugin tests.

## Packaging

After building and testing, `python tools/package_release.py` creates the source
ZIP, Windows ZIP, separate plugin JAR and SHA256SUMS.txt under `dist/`. It packages
tracked source files, excluding its `dist/` and `build/` outputs; stage any new
release source files before running it. It requires the Windows executable and
the plugin JAR to have been built already.
