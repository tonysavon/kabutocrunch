// Run the commands in examples/README.md first.
#define ZX0RAW
* = $0801
BasicUpstart2(start)
start:
    sei
    cld
    lda #$34
    sta $01
    :ZX0_RAWDECRUNCH(packed, $4000)
done:
    jmp done
#import "../src/asm/dcrunch.asm"
* = $8000
packed:
    .import binary "data.lz"
