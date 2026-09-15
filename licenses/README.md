# Third-party notices

Kabutocrunch is derived from Dali/Bitfire and Salvador, using the ZX0 design.
Altered files are part of Kabutocrunch, not an unmodified upstream release.

- Dali/Bitfire: Tobias Bindhammer; BSD 3-Clause. Includes the original SFX
  source and Dali-derived decoder code. See Dali-BSD-3-Clause.txt and the
  retained complete notice in src/asm/sfx/dali_sfx.asm.
- Salvador: Emmanuel Marty; zlib license (Salvador-zlib.txt).
- Match finder: Emmanuel Marty and Eric Biggers; CC0 (Salvador-CC0.txt).
- libdivsufsort: Yuta Mori; MIT license (libdivsufsort-MIT.txt).
- ZX0 encoding design: Einar Saukas, credited in the inherited source.

Individual source notices remain authoritative for their respective portions.
Keep these notices with source and binary distributions.

The plugin's Java encoder is an altered port of the Salvador-derived C code;
its parser and match finder retain their zlib and CC0 notices. Its suffix-array
implementation uses SA-IS rather than libdivsufsort. The KickAssembler interface
wrapper is Kabutocrunch code under the root zlib license. KickAssembler itself
and other cruncher plugins are not included in the JAR.
