# Kabutocrunch for KickAssembler

Use `.modify KABUTO()` to compress assembled code and data. The plugin is shipped
with Kabutocrunch and uses a Java port of its encoder. It does not launch the C
executable, require native libraries, or provide a Java command-line cruncher.

Tested with KickAssembler 5.25. Requires Java 11 or newer. KickAssembler itself
is not bundled: obtain it from its author under its own license.

## Install and run

Put `kabutocrunch-kickass.jar` on the Java classpath alongside `KickAss.jar`.
Use a semicolon on Windows:

```powershell
java -Xmx1g -cp "C:/tools/KickAss.jar;build/kabutocrunch-kickass.jar" kickass.KickAssembler examples/plugin/raw.asm
```

Use a colon on Linux/macOS:

```sh
java -Xmx1g -cp "/path/to/KickAss.jar:build/kabutocrunch-kickass.jar" kickass.KickAssembler examples/plugin/raw.asm
```

Adjust the plugin path if using the downloaded JAR. Use `-cp` with
`kickass.KickAssembler`, as above; `java -jar` does not use this classpath.
If your IDE already loads plugin JARs, add this JAR through that mechanism and
import the plugin in your assembly source:

```asm
.plugin "kabutocrunch.KABUTO"
```

The parser allocates sizeable working arrays. `-Xmx1g` leaves room for both the
encoder and assembler. Calls have independent settings and do not retain a
compression cache between assemblies.

## Parameters

```asm
.modify KABUTO()                 // native raw, speed 2
.modify KABUTO(false)            // native Mem, speed 2
.modify KABUTO(true, true)        // Dali raw, speed 2
.modify KABUTO(false, true, 0)    // Dali Mem, size-first parsing
```

| Position | Parameter | Default | Meaning |
|---|---|---|---|
| 1 | `raw` | `true` | Raw stream; `false` includes a destination header (Mem) |
| 2 | `dali` | `false` | Native Kabutocrunch; `true` matches C `--dali` |
| 3 | `speed` | `2` | Integer 0..15, matching C `--speed` |

Arguments are positional, with optional trailing arguments. `raw=true` inside
the argument list is not supported by KickAssembler's modifier syntax.

## Multiple memory regions

```asm
* = $8000
packed:
.modify KABUTO() {
    .pc = $1000 "code"
    lda #$06
    sta $d020
    rts

    .pc = $4000 "data"
    .fill 512, i & $ff
}
```

Regions are sorted by address, so reverse order works too. Gaps are filled with
zeros before compression and overwritten with zeros during decompression. This
example expands into `$1000..$41ff`, including the gap. Labels inside the modifier
keep their original addresses. Packed bytes are emitted at the outer PC (`$8000`).

Empty regions are ignored; an entirely empty modifier is an error. Overlapping
regions, addresses outside `$0000..$ffff`, and merged spans above 65,535 bytes are
rejected. A region may end at `$ffff`. The maximum span includes all gaps.

This follows Exomizer Mem's contiguous-image approach. KABUTO supports multiple
regions in raw mode as well. It does not emit separately relocated blocks or
preserve memory in the gaps.

## Decompression

| Mode | Define before importing decoder | Call |
|---|---|---|
| Native raw | `#define KABUTORAW` | `:KABUTO_RAWDECRUNCH(packed, destination)` |
| Native Mem | No raw define | `:KABUTO_DECRUNCH(packed)` |
| Dali raw | `#define ZX0RAW` | `:ZX0_RAWDECRUNCH(packed, destination)` |
| Dali Mem | No raw define | `:ZX0_DECRUNCH(packed)` |

Import `src/asm/dcrunch.asm` for native streams or `src/asm/dcrunch_dali.asm`
for Dali. Mem prefixes the raw stream with the original destination **high byte
then low byte**, as expected by these decoder entry points. It is not a PRG
container; do not add a PRG load header inside the stream.

Both modes use ordinary, non-in-place decompression. Keep the decoder, caller,
stack, used zero page and packed bytes outside the destination span. A valid
encoder input size does not guarantee a safe C64 memory layout. The plugin does
not provide SFX, automatic in-place placement, or prefix dictionaries; use the C
cruncher for those workflows.

Complete examples that decompress and execute generated code:

* [Native raw](../examples/plugin/raw.asm)
* [Native Mem](../examples/plugin/mem.asm)
* [Dali raw](../examples/plugin/raw_dali.asm)
* [Dali Mem](../examples/plugin/mem_dali.asm)

The test suite assembles each example in both ascending and descending region
order, executes its decoder, checks every output/gap byte, and calls the restored
code. These are 6502 instruction-level tests, not VIC/CIA timing tests.

## Build and test

Requires a JDK (including `javac`), Python 3.10+ and your KickAssembler JAR:

```sh
python tools/build_plugin.py --kickass-jar /path/to/KickAss.jar
python tools/test_plugin.py --kickass-jar /path/to/KickAss.jar
```

Or use `make plugin` and `make test-plugin` with `KICKASS=/path/to/KickAss.jar`.
The build produces `build/kabutocrunch-kickass.jar`, targeting Java 11. Tests
additionally require GCC and the root `requirements-test.txt` dependencies.
Test drivers and the test-only Java decoder are excluded from the JAR.

The encoder is derived from the project's Salvador-based C implementation;
SA-IS replaces libdivsufsort for suffix-array construction. See the retained
notices and [licenses](../licenses). Tests compare Java/C output byte for byte
and use both the C verifier and assembled 6502 decoders.
