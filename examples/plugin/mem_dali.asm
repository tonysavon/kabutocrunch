.plugin "kabutocrunch.KABUTO"
* = $0801
BasicUpstart2(start)
start:
    sei
    cld
    lda #$34
    sta $01
    :ZX0_DECRUNCH(packed)
    lda #$35
    sta $01
    jsr first
done:
    jmp done
#import "../../src/asm/dcrunch_dali.asm"
* = $8000
packed:
.modify KABUTO(false, true, 2) {
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
