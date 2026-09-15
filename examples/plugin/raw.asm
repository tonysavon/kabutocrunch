.plugin "kabutocrunch.KABUTO"
#define KABUTORAW
* = $0801
BasicUpstart2(start)
start:
    sei
    cld
    lda #$34
    sta $01
    :KABUTO_RAWDECRUNCH(packed, $1000)
    lda #$35
    sta $01
    jsr first
done:
    jmp done
#import "../../src/asm/dcrunch.asm"
* = $8000
packed:
.modify KABUTO() {
    .pc = $1000 "first region"
first:
    lda #$06
    sta $d020
    rts
    .fill 250, i
    .pc = $4000 "second region"
second:
    .fill 512, (i * 37) & $ff
}
packedEnd:
