// Preserve the calculated packed address; skip load and destination headers.
#define ZX0RAW
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
    :ZX0_RAWDECRUNCH(packedAddress, destination)
done:
    jmp done
#import "../src/asm/dcrunch.asm"
* = packedAddress
    .import binary "data-inplace.prg", 4
