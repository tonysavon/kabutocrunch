# Dali SFX source

`dali_sfx.asm` is copied from `packer/dali/sfx.asm` in
https://github.com/bboxy/bitfire at commit
`f443e652f00384a8ed53eb115826b580c2618a16` (Tobias Bindhammer).
Its BSD license is retained in the source. Distribute that notice with binaries.
Kabutocrunch adds an assembly size assertion; the decoder is otherwise original.

The loader copies its decoder to $01..$ff, using an eight-bit index, and
relocates compressed data to the top of RAM. The 328-byte previous compact
Dali decoder cannot fit this 255-byte budget, even before adding SFX setup
and exit code. The original SFX uses its own zero-page-specialized decoder.
Normal/normal-effect variants preserve used zero page and restore $01;
small variants leave zero page overwritten and $01=$34.

Rebuild `src/sfx.h` with `python tools/build_sfx.py --acme path/to/acme`.
The header is committed so ordinary C builds do not require an assembler.
