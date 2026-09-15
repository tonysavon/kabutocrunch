# kabutocrunch

Kabutocrunch is a Commodore 64 cruncher built around small, fast 6502
decrunchers. It draws on ZX0's LZ coding ideas and TSCrunch's explicit RLE
support, with a dedicated fast path for repeated bytes. Its proprietary
bitstream and decoder modifications make decrunching faster than Dali.

Dali compatibility is supported through the `--dali` switch, and a faster
Dali-compatible decoder is provided.

To make the transition easy, Kabutocrunch preserves Dali's command-line
conventions while adding its own commands and options. This makes it easier
to adopt Kabutocrunch in existing projects and build scripts. Use `--dali`
when your project requires Dali-compatible packed data.

The C99 compressor builds on Salvador's match finder and parser; upstream
credits and license notices are retained.

## Build

```sh
make
```

Requires GCC (or a compatible C99 compiler) and GNU Make. The build creates
`kcrunch` on Unix or `kcrunch.exe` on Windows (MSYS2/MinGW).
The normal build uses `-Wall -Wextra -Werror`. No assembler is needed to build
the cruncher: the SFX byte arrays are checked in.

## Encode and Verify

```sh
# C64 PRG input, preserving its load address in the packed file
./kcrunch game.prg

# Raw binary input, suitable for the raw ASM decruncher
./kcrunch --binfile --no-inplace -o game.lz game.bin

# Compress and byte-verify with the matching C decoder
./kcrunch --verify game.prg

# Emit and verify a standard Dali stream
./kcrunch -dali --verify game.prg
```

`--short` limits emitted match runs to 256 bytes. Literal runs remain
unbounded because the command grammar does not permit consecutive literal
runs.

`--speed N` biases the parser toward fewer commands without changing the
bitstream grammar or decoder. The default is `--speed 2`; use `--speed 0` for
the smallest output, or a higher value when decrunch time matters more than
packed size.

`--dali` (also `-dali`) selects Dali-compatible streams for
compression, decompression, and verification. It does not alter the parser:
`-dali --speed 0` gives the size-first Dali parse, while plain `-dali` uses the
faster default parse.

## 6502 Decruncher

[`src/asm/dcrunch.asm`](src/asm/dcrunch.asm) provides the native Kabutocrunch
decruncher. Its default build is 397 bytes and measures 26.755 cycles/output
byte with the default parser setting, using **22.65% fewer cycles than the
original Dali decoder** (321 bytes, 34.591 cycles/output byte). Comment out
`KCRUNCH_FAST_LITERALS` for a 339-byte build measuring 29.958 cycles/output
byte, still **13.39% fewer cycles than original Dali**.

[`src/asm/dcrunch_dali.asm`](src/asm/dcrunch_dali.asm) provides Kabutocrunch's
optimized Dali-compatible decoder. It is 395 bytes and measures 28.046
cycles/output byte with the default parse: **18.92% fewer cycles than original
Dali**, while retaining Dali bitstream compatibility. With `--speed 0`, it
measures 31.407 cycles/output byte, a **9.20% cycle reduction**.

These measurements cover the same six-game corpus. Default native output is
2.73% larger than the size-first Dali baseline; default Dali-compatible output
is 1.22% larger. See [performance details](docs/performance.md).

## Calling the decoder from your code

Complete KickAssembler examples are in [examples](examples/README.md), covering
regular decompression into a separate buffer and in-place decompression.

```asm
// Regular: compress with --binfile --no-inplace.
#define KABUTORAW
* = $0801
BasicUpstart2(start)
start:
    sei
    cld
    lda #$34
    sta $01                 // RAM visible; interrupts remain disabled
    :KABUTO_RAWDECRUNCH(packed, $4000)
done:
    jmp done                // decoded data is now at $4000
#import "src/asm/dcrunch.asm"
* = $8000
packed:
    .import binary "data.lz"
```

For **in-place** decompression, also define `INPLACE`, compress a PRG with
`--inplace`, and keep the packed stream at its calculated address. The PRG's
first two bytes specify its load address; the next two specify the original
destination. The example below skips both headers and calls the raw entry:

```asm
#define KABUTORAW
#define INPLACE
.var packedFile = LoadBinary("data-inplace.prg")
.const packedAddress = (packedFile.get(0) & $ff) + 256 * (packedFile.get(1) & $ff) + 2
.const destination = (packedFile.get(2) & $ff) + 256 * (packedFile.get(3) & $ff)
* = $0801
BasicUpstart2(start)
start:
    sei
    cld
    lda #$34
    sta $01
    :KABUTO_RAWDECRUNCH(packedAddress, destination)
done:
    jmp done
#import "src/asm/dcrunch.asm"
* = packedAddress
    .import binary "data-inplace.prg", 4
```

These examples leave interrupts disabled and `$01=$34`. Restore the memory
mapping and interrupt state required by your program after the call. The
raw entry clobbers A/X/Y, flags, and zero page `$f8..$ff`; decoder code must
be writable RAM. Keep the decoder, stack, and caller outside the output area.
For regular decompression, keep packed input outside the output area too.
In-place streams require `INPLACE`; regular streams use the default build.

### Dali-compatible calls

The Dali decoder retains its `ZX0` names. Native Kabutocrunch uses `KABUTO`
names; choose the interface that matches the compressed stream.

| Interface | Native Kabutocrunch | Dali-compatible |
|---|---|---|
| Raw define | `KABUTORAW` | `ZX0RAW` |
| Raw macro | `KABUTO_RAWDECRUNCH(src, dst)` | `ZX0_RAWDECRUNCH(src, dst)` |
| Raw entry | `kabuto.rawdecrunch` | `zx0.rawdecrunch` |
| Header-based macro | `KABUTO_DECRUNCH(addr)` | `ZX0_DECRUNCH(addr)` |
| Header-based entry | `kabuto.decrunch` | `zx0.decrunch` |

Compress with `--dali` and use the Dali decoder like this:

```asm
#define ZX0RAW
// Add #define INPLACE for a --dali --inplace stream.
// Inside your routine, after setting banking/interrupts as shown above:
    :ZX0_RAWDECRUNCH(packed, $4000)
// Place the decoder in writable RAM, outside the output region:
#import "src/asm/dcrunch_dali.asm"
```

Complete Dali examples: [regular](examples/regular_dali.asm) and
[in-place](examples/inplace_dali.asm). The same `INPLACE` define applies to
both decoder families. For direct raw calls, Y/X contain the source low/high
bytes and `lz_dst` contains the destination address.

## Cycle Harness

The Python harness assembles the same raw fast decruncher, compresses the
usual local PRG corpus, simulates 6502 execution with `py65`, and validates
the bytes written during every run:

```sh
python -m pip install py65
make cycles KICKASS=/path/to/KickAss.jar
```

The report includes assembled decoder size as well as cycles and cycles/byte.
It automatically includes sibling C64 projects when they are present. Pass
explicit PRGs to benchmark another set:

```sh
python tools/benchmark_cycles.py path/to/game.prg

# Benchmark standard Dali with its matching decoder
python tools/benchmark_cycles.py --dali --speed 0
```

See [`docs/performance.md`](docs/performance.md) for the measured baseline,
current results, and the decoder changes.

## Self-extracting PRGs

```sh
./kcrunch --sfx 4096 -o game-sfx.prg game.prg
./kcrunch --sfx 4096 --effect --01 55 --cli -o game-sfx.prg game.prg
```

`--sfx` automatically selects plain Dali coding, retaining the requested
`--speed` setting. The original Dali SFX source is copied and attributed in
[`src/asm/sfx`](src/asm/sfx/README.md); it is assembled into the checked-in
`src/sfx.h`. The 328-byte previous compact Dali decoder exceeds the loader's
255-byte zero-page budget, so SFX uses the original specialized Dali decoder.

| SFX variant | Zero-page bytes | PRG overhead, including load address |
|---|---:|---:|
| Default | 236 | 270 |
| `--effect` | 243 | 277 |
| `--small` | 183 | 211 |
| `--small --effect` | 190 | 218 |

The default restores used zero page, sets `$01` to `$37` (or `--01`), and
leaves interrupts disabled unless `--cli` is given. `--small` leaves zero
page overwritten and `$01=$34`; it ignores `--01` and `--cli`.
With `--cli`, the selected memory mapping must expose a valid IRQ handler;
use `$37` for the normal KERNAL handler.
`--relocate-sfx ADDRESS` omits the 12-byte BASIC header; enter at that address.
Output must avoid zero page/stack and must not overwrite unread packed data
at the top of RAM. Invalid layouts are rejected before opening the output file.
Prefix dictionaries and C verify/decode modes cannot be combined with SFX.

## Release tests

With GCC, Python 3.10+, py65, Java and KickAssembler available (set
`KICKASS_JAR` or pass `--kickass-jar` for direct Python invocations):

```sh
python -m pip install -r requirements-test.txt
make test KICKASS=/path/to/KickAss.jar
python tools/test_release.py --kickass-jar /path/to/KickAss.jar --corpus
python tools/build_sfx.py --acme path/to/acme --check
python tools/smoke_vice.py --vice path/to/x64sc
```

The self-contained suite checks both raw formats in C and emulated 6502 code,
length/page boundaries, long offsets, repeated decoder calls, speed profiles,
short matches, all SFX variants, relocation, RAM under ROM/I/O, CPU state,
and invalid SFX layouts. `--corpus` adds the available sibling game projects.
SFX tests execute the actual generated PRG from its machine-code entry through
the final jump. These are instruction-level tests with memory banking, not a
replacement for a final VICE or real-C64 smoke test with VIC/CIA timing.
The VICE smoke test checks all eight variant/relocation combinations, with
output extending under BASIC ROM, I/O and KERNAL ROM.

## Credits

- TSCrunch by Antonio Savona ? explicit RLE inspiration
- ZX0 by Einar Saukas
- Salvador by Emmanuel Marty
- Dali / Bitfire by Tobias Bindhammer

## Distribution

See [RELEASE.md](RELEASE.md) for the file manifest and validation record.
The Windows executable is distributed separately from source.
Original upstream notices are retained in [licenses](licenses); see
[LICENSE](LICENSE) for the license applying to Kabutocrunch additions.
