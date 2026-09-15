# Decompression performance

Measured on 2026-09-03 with `tools/benchmark_cycles.py`. The harness assembles
the raw decoder with KickAssembler, runs it under `py65`, validates every
output write, and reports 6502 cycles over six C64 games (329,268 output
bytes).

## Results

| Decoder / parser | Bytes | Packed bytes | Total cycles | Cycles/output byte | vs original |
|---|---:|---:|---:|---:|---:|
| Original Dali | 321 | 135,086 | 11,389,604 | 34.590680 | baseline |
| Previous compact Dali | 328 | 135,086 | 11,144,230 | 33.845469 | -2.15% |
| Fast Dali, `--speed 0` | 395 | 135,086 | 10,341,358 | 31.407115 | -9.20% |
| Fast Dali, default `--speed 2` | 395 | 136,738 | 9,234,789 | 28.046421 | -18.92% |
| Raw-high, `--speed 0` | 397 | 137,106 | 9,876,530 | 29.995414 | -13.30% |
| Raw-high compact, default parse | 339 | 138,772 | 9,864,287 | 29.958232 | -13.39% |
| Raw-high, default `--speed 2` | 397 | 138,772 | 8,809,705 | 26.755424 | -22.65% |
| Raw-high, `--speed 4` | 397 | 141,906 | 8,025,071 | 24.372460 | -29.54% |

The raw-high default is 14.81% faster than the size-first 395-byte Dali
decoder while being two bytes larger. Its packed output is 2.73% larger
than the size-first Dali parse. The raw-high format alone (`--speed 0`)
accounts for a 4.50% speed improvement and a 1.50% packed-size increase; the
remaining default gain comes from selecting fewer commands.

`-dali` selects the standard Dali grammar and `src/asm/dcrunch_dali.asm`.
With the same default parse it reaches 28.046421 cycles/byte, so raw-high is a
further 4.60% faster at a 1.49% packed-size cost. `-dali --speed 0` reproduces
the previous size-first totals in the table.

Comment out `KCRUNCH_FAST_LITERALS` in `src/asm/dcrunch.asm` for the 339-byte
compact build.

## Parser speed profiles

`--speed N` adds a virtual token penalty during parsing. It does not change
the stream grammar or decoder, so all speed profiles within either selected
format are mutually compatible. The setting lets the encoder accept a small
size cost for longer literal/match runs and fewer command and length decodes.

| Speed | Packed bytes | Size vs Dali | Cycles/output byte | vs previous fast Dali |
|---:|---:|---:|---:|---:|
| 0 | 137,106 | +1.50% | 29.995414 | -4.50% |
| 1 | 137,433 | +1.74% | 28.706573 | -8.60% |
| **2 (default)** | **138,772** | **+2.73%** | **26.755424** | **-14.81%** |
| 3 | 140,585 | +4.07% | 25.397357 | -19.14% |
| 4 | 141,906 | +5.05% | 24.372460 | -22.40% |
| 5 | 143,545 | +6.26% | 23.479479 | -25.24% |
| 6 | 145,464 | +7.68% | 22.471877 | -28.45% |
| 7 | 146,887 | +8.74% | 21.874792 | -30.35% |

## Default build by game

| Game | Packed | Output | Previous Dali cycles | Current cycles | Change |
|---|---:|---:|---:|---:|---:|
| kabutocrunch | 11,006 | 46,911 | 1,373,247 | 1,208,064 | -12.03% |
| crackpots | 13,717 | 56,295 | 1,676,036 | 1,421,159 | -15.21% |
| abbey | 45,196 | 56,832 | 2,147,630 | 1,681,732 | -21.69% |
| fixit | 39,249 | 59,392 | 1,893,135 | 1,623,979 | -14.22% |
| pigquest | 14,095 | 46,592 | 1,297,366 | 1,105,586 | -14.78% |
| keystone | 15,509 | 63,246 | 1,953,980 | 1,769,185 | -9.46% |

## Raw-high bitstream change

The literal/repeat/new-offset command grammar and Dali-derived length code are
retained. A new-offset command now encodes its distance as follows:

- selector bit 1: distance 1..128, followed by the existing metadata byte;
- selector bit 0: one raw high-group byte, then the metadata byte;
- raw high-group byte 0: end of stream.

The metadata byte still stores seven low distance bits in bits 7..1 and the
first match-length bit in bit 0. Long offsets therefore take a fixed 16 bits
(selector, raw high byte, seven low bits) before the remaining length bits.
The parser uses those actual costs. This removes the long-offset Elias loop
from the 6502 decoder and makes the format intentionally incompatible with
Dali.

## Other retained decoder changes

- Literal pointer page crossings use rare-case branches.
- Match stores use a self-modified absolute,Y destination.
- Control-bit refill branches favor the seven non-refill cases in each byte.
- Runs of eight or more literals patch only the destination and index the
  source indirectly, retaining most of the speed benefit within 397 bytes.
- Multi-page matches advance patched source and destination high bytes.
- Distance-1 matches use a store-only continuation loop.
- The assembled match branch starts in the distance-1 state, avoiding five
  bytes of runtime initialization. `dcrunch_dali.asm` now shares this
  optimization (previously only `dcrunch.asm` had it): its raw fast-path
  init assembled the `lz_match_loop` branch for the wrong initial state and
  patched it at runtime with `lda #MATCH_LOOP_RLE` / `sta lz_match_loop + 1`.
  Assembling the correct default (`bne lz_cp_store`) removes that five-byte
  runtime patch, shrinking `dcrunch_dali.asm` from 400 to 395 bytes and
  saving a handful of cycles on every decrunch call with no bitstream or
  behavioral change.
- The raw-high short-offset path (`dcrunch.asm` only; the Dali stream has no
  matching pattern) used `lda #$00` / `sta lz_offset_hi + 1` / `clc` to zero
  the offset high byte and clear carry before the metadata `ror`. The
  undocumented, stable `anc #$00` opcode ANDs the accumulator with an
  immediate value and copies bit 7 of the result into carry in one 2-byte,
  2-cycle instruction; since the operand is `#$00` the result is always
  `A = 0, C = 0`, exactly what the old three-instruction sequence produced.
  Replacing them with `anc #$00` saves one byte and two cycles on every
  short new-offset command, shrinking `dcrunch.asm` to 397 bytes.
  `tools/benchmark_cycles.py`'s `py65` harness needed `ANC` ($0B/$2B) added
  to `install_undocumented` since py65 doesn't emulate it natively.

## Rejected experiments

The old dormant `ZX0_SHORT_OFFSETS` block was removed. Its selector disturbed
the payload/control-pair alignment assumed by the fast Elias reader.

A denser three-tier offset prototype directly coded high groups 1..3 and used
a raw-byte escape for larger values. It limited packed growth to 0.68%, but
only reached 30.650 cycles/byte and grew the decoder to 452 bytes. The retained
raw-high design is both faster and substantially smaller.

Replacing the fast-literal length test's `and #$f8` / `bne` with the
undocumented `alr #$f8` (in both decoders, right before
`lz_cp_lit_fast_setup`) looked like a free byte/cycle win: `ALR` fuses AND
and LSR, and the branch's zero/nonzero outcome is unaffected by the shift.
It was rejected because `AND` leaves carry untouched, while `ALR` always
overwrites it with bit 0 of the masked value (always 0 for mask `$f8`). The
carry entering this point is not scratch: it holds a payload bit from the
preceding Elias length decode that `lz_literal_complete`'s `rol` still needs
whenever the literal run is short enough that the fast-copy branch isn't
taken. `py65`'s cycle-accurate harness caught the resulting stream desync
immediately as a wrong-output-byte failure across every test PRG.
