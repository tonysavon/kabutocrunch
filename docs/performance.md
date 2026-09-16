# Decompression performance

Measured on 2026-09-03 with `tools/benchmark_cycles.py`. The harness assembles
the raw decoder with KickAssembler, runs it under `py65`, validates every
output write, and reports 6502 cycles over six C64 games (329,268 output
bytes).

## Results

| Decoder / parser | Bytes | Packed bytes | Total cycles | Cycles/output byte | Cycle reduction vs original Dali |
|---|---:|---:|---:|---:|---:|
| Original Dali | 321 | 135,086 | 11,389,604 | 34.590680 | baseline |
| Earlier compact Dali-compatible | 328 | 135,086 | 11,144,230 | 33.845469 | -2.15% |
| KabutoCrunch Dali-compatible, `--speed 0` | 395 | 135,086 | 10,341,358 | 31.407115 | -9.20% |
| KabutoCrunch Dali-compatible, default `--speed 2` | 395 | 136,738 | 9,234,789 | 28.046421 | -18.92% |
| KabutoCrunch, `--speed 0` | 397 | 137,106 | 9,876,530 | 29.995414 | -13.28% |
| KabutoCrunch compact, default parse | 339 | 138,772 | 9,864,287 | 29.958232 | -13.39% |
| KabutoCrunch, default `--speed 2` | 397 | 138,772 | 8,809,705 | 26.755424 | -22.65% |
| KabutoCrunch, `--speed 4` | 397 | 141,906 | 8,025,071 | 24.372460 | -29.54% |

All improvement percentages use the **original 321-byte Dali decoder** as
the baseline: 11,389,604 cycles, or 34.590680 cycles/output byte. The optimized
Dali-compatible decoder is a KabutoCrunch improvement, not the baseline.
Percentages describe reductions in cycle count, rather than throughput gains.

Native KabutoCrunch at its default setting uses **22.65% fewer cycles** than
original Dali, with a 397-byte decoder (76 bytes larger). Packed output is
2.73% larger than the size-first Dali baseline. At `--speed 0`, native
KabutoCrunch uses 13.28% fewer cycles with 1.50% larger packed output.
These gains include decoder changes; they are not attributed to the bitstream
alone.

`--dali` retains Dali bitstream compatibility and uses
`src/asm/dcrunch_dali.asm`. This optimized decoder uses **18.92% fewer cycles**
than original Dali with the default parse, with 1.22% larger packed output.
At `--speed 0`, it uses **9.20% fewer cycles** with the same total packed size
as the original Dali baseline.

Comment out `KCRUNCH_FAST_LITERALS` in `src/asm/dcrunch.asm` for the 339-byte
compact build.

## Parser speed profiles

`--speed N` adds a virtual token penalty during parsing. It does not change
the stream grammar or decoder, so all speed profiles within either selected
format are mutually compatible. The setting lets the encoder accept a small
size cost for longer literal/match runs and fewer command and length decodes.

| Speed | Packed bytes | Size vs Dali | Cycles/output byte | Cycle reduction vs original Dali |
|---:|---:|---:|---:|---:|
| 0 | 137,106 | +1.50% | 29.995414 | -13.28% |
| 1 | 137,433 | +1.74% | 28.706573 | -17.01% |
| **2 (default)** | **138,772** | **+2.73%** | **26.755424** | **-22.65%** |
| 3 | 140,585 | +4.07% | 25.397357 | -26.58% |
| 4 | 141,906 | +5.05% | 24.372460 | -29.54% |
| 5 | 143,545 | +6.26% | 23.479479 | -32.12% |
| 6 | 145,464 | +7.68% | 22.471877 | -35.03% |
| 7 | 146,887 | +8.74% | 21.874792 | -36.76% |

## Default build by game

Per-game native results are shown below. Original-Dali per-game counts are
not recorded here, so baseline comparisons use the corpus totals above.

| Game | Packed | Output | Native cycles | Cycles/output byte |
|---|---:|---:|---:|---:|
| kabutocrunch | 11,006 | 46,911 | 1,208,064 | 25.752254 |
| crackpots | 13,717 | 56,295 | 1,421,159 | 25.244853 |
| abbey | 45,196 | 56,832 | 1,681,732 | 29.591287 |
| fixit | 39,249 | 59,392 | 1,623,979 | 27.343396 |
| pigquest | 14,095 | 46,592 | 1,105,586 | 23.729095 |
| keystone | 15,509 | 63,246 | 1,769,185 | 27.973073 |

## KabutoCrunch format

KabutoCrunch combines its proprietary bitstream with decoder optimizations,
explicit RLE support and parser speed profiles. The measured gains reflect
these changes together. Use `--dali` for Dali-compatible output and the
provided Dali-compatible decoder.
