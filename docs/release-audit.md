# Release audit, 2026-09-14

The existing KabutoCrunch decoder remains 397 bytes and reproduced the recorded
8,809,705 cycles over 329,268 output bytes (26.755424 cycles/byte). No decoder
or stream change was warranted by this review. SFX is now enabled using the
original Dali source, with automatic selection of Dali coding.

## Remaining optimization opportunities

The main measurable opportunity is host compression speed, without adding
anything to the decoder. `tools/audit_search.py` compared the existing parser
budget with a leaner budget and a wider search on the six local games, using
`-dali --speed 0`. Every result was byte-verified with the C decoder.

| Search | Packed bytes | Change | Host seconds |
|---|---:|---:|---:|
| Existing (109 arrivals, 78 matches) | 135,086 | baseline | 6.142 |
| Lean (64 arrivals, 48 matches) | 135,092 | +6 | 3.849 |
| Wide (160 arrivals, 100 matches, no long-match early break) | 135,079 | -7 | 15.493 |

Full configurations and per-game results are in `tools/audit_search.py` and
`benchmarks/search-audit.json`. Timings are indicative single runs on this
Windows machine, with other test work running; they are not stable performance
guarantees. Packed sizes are deterministic. The lean configuration is a useful
candidate for an optional fast-compression profile. The wide search saved only
seven bytes total and does not justify a new default. Neither was promoted into
the production encoder during release preparation; the existing size behavior
is retained. This sample does not establish an optimal parse on every input.

For further *C64* speed, the most promising decoder-size-neutral work is a
more detailed parser cost model: distinguish literal-copy setup, short and long
offsets, distance-one matches, and page-spanning runs. The current `--speed`
penalty treats commands uniformly. Such a model needs separate measurements
for KabutoCrunch, compact, and SFX decoders; it is a future experiment, not a
measured gain claimed by this audit. Existing `--speed` settings already offer
a substantial measured size/speed tradeoff.

The current fast literal copy and distance-one match loop already address
the major per-byte costs. Loop unrolling or more special cases consume decoder
space; changing offset grammar would also invalidate existing streams. There
was no demonstrated small, drop-in decoder win to include in this release.

## SFX decision and validation

Source: `packer/dali/sfx.asm` from the local Bitfire repository at commit
`f443e652f00384a8ed53eb115826b580c2618a16`, copied with its copyright/license.
Only a zero-page-size assembly assertion was added. See `src/asm/sfx/README.md`.

The previous compact Dali decoder is 328 bytes and cannot fit the original
loader's $01..$ff relocation area. The original specialized SFX decoder uses
236 bytes, or 243 with its border effect. Small variants use 183/190 bytes.
The C build embeds reproducibly generated arrays; it needs no assembler.

Validation completed:

- Warning-clean GCC build with `-Wall -Wextra -Werror`.
- 490 checks from `python tools/test_release.py --corpus`: C round trips,
  raw 6502 execution, repeated decoder use, length/page/offset boundaries,
  parser speed settings and short matches, all SFX variants and relocation,
  memory banking, zero-page/stack/CPU-state checks, six games, and invalid layouts.
- Eight full-machine VICE SFX executions, each byte-compared against its input,
  covering all four variants with and without relocation. The fixture extends
  under BASIC ROM, I/O and KERNAL ROM.
- Exact regeneration check for all four SFX arrays from the vendored source.
- Existing KickAssembler checksum harness assembles successfully.

The first VICE IRQ-enabled test used `$01=$35`, banking out the KERNAL without
installing a replacement IRQ vector. Using `$37` with `--cli` passed, as expected.
Custom mappings with interrupts enabled require a valid visible IRQ handler;
this is documented and the original Dali exit behavior is retained.

Unsafe SFX output/load addresses, output wrapping, unread-stream overlap,
oversized input and incompatible dictionary/verify modes are rejected before
the output file is opened. The host overlap check adds no bytes to the decoder.

No release-blocking failure remains in these checks. No tag or release was
published by this audit.
