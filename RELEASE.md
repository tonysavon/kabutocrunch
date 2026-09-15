# Initial private release preparation

## Source repository contents

- `src/*.c`, `src/*.h`, `src/libdivsufsort/`: C99 encoder/decoder, required
  dependency sources, and generated SFX arrays.
- `src/asm/dcrunch.asm`, `dcrunch_dali.asm`: matching C64 decoders.
- `src/asm/sfx/`: original Dali SFX assembly, provenance, and size assertion.
- `Makefile`, `README.md`, `LICENSE`, `licenses/`: build, usage and licenses.
- `examples/`: runnable regular and in-place KickAssembler integration examples.
- `tools/`, `requirements-test.txt`: deterministic release tests, VICE smoke
  tests, SFX regeneration, and performance/search measurement tools.
- `docs/`, `benchmarks/search-audit.json`: performance results and audit evidence.

Game binaries, copyrighted test corpus, development history, stale SFX stubs,
IDE files, emulator/assembler binaries, and generated debug files are excluded.
Tests generate their own data. The optional local corpus is not distributed.
The original game-specific checksum harness is development-only and excluded.

## Windows binary package

`kcrunch.exe`, README, LICENSE, all third-party license notices, both assembly
 decoders, and Dali SFX source/provenance. The executable is built from the
same source snapshot as this repository. SHA-256 accompanies the archive.

## Validation

The preceding audit passed 490 checks including six local games and eight
full-machine VICE SFX executions. On 2026-09-15 the clean release snapshot passed all 442 self-contained
round-trip/execution checks and eight full-machine VICE SFX checks. GCC
built it with -Wall -Wextra -Werror; all four SFX arrays regenerated exactly.
Historical corpus measurements remain in docs/.

SFX automatically selects Dali. Use `$01=$37` with `--cli` for the standard
KERNAL IRQ handler; custom mappings require an appropriate visible handler.

The four integration examples (native/Dali, regular/in-place) also assemble
and byte-check successfully; `make test` now includes them.
