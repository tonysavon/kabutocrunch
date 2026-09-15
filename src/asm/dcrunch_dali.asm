// Fast decoder for the classic Dali-compatible bitstream.
//#define ZX0RAW
// Comment out for a smaller decoder that keeps the other hot-path wins.
#define KCRUNCH_FAST_LITERALS

#if ZX0RAW
#define ZX0RAW_FAST
.macro ZX0_RAWDECRUNCH(src,dst)
{
	ldy #<src
	ldx #>src

	lda #<dst
	sta.zp lz_dst
	lda #>dst
	sta.zp lz_dst + 1

	jsr zx0.rawdecrunch
}
#else
.macro ZX0_DECRUNCH(addr)
{
	ldy #<addr
    ldx #>addr
    jsr zx0.decrunch
}
#endif

//#define INPLACE

.label CONFIG_ZP_ADDR	= $f8
.const LZ_BITS_LEFT     = 1

.label lz_bits			= CONFIG_ZP_ADDR + 0
.label lz_dst			= CONFIG_ZP_ADDR + 1
.label lz_src			= CONFIG_ZP_ADDR + 3
#if ZX0RAW
.label lz_len_lo		= CONFIG_ZP_ADDR + 5
.label lz_len_hi		= CONFIG_ZP_ADDR + 6
.label lz_bit_byte		= CONFIG_ZP_ADDR + 7
#else
.label lz_len_hi		= CONFIG_ZP_ADDR + 5
#endif



.macro get_lz_bit() {
        .if (LZ_BITS_LEFT == 1) {
                asl lz_bits
        } else {
                lsr lz_bits
        }
}

.macro set_lz_bit_marker() {
        .if (LZ_BITS_LEFT == 1) {
                rol
        } else {
                ror
        }
}

.macro init_lz_bits() {
        .if (LZ_BITS_LEFT == 1) {
                        lda #$40
                        sta lz_bits                    //start with an empty lz_bits, first +get_lz_bit leads to literal this way and bits are refilled upon next shift
        } else {
                        stx lz_bits
        }
}

//---------------------------------------------------------------------------------
//DEPACKER STUFF
//---------------------------------------------------------------------------------
zx0:
{
	.const MATCH_LOOP_NORMAL = (lz_cp_match - (lz_match_loop + 2)) & $ff
	.const MATCH_LOOP_RLE    = (lz_cp_store - (lz_match_loop + 2)) & $ff

	#if ZX0RAW
	rawdecrunch:
			stx lz_src + 1
			sty lz_src + 0
#if ZX0RAW_FAST
			ldx #$02
			:init_lz_bits()
			ldy #$00                        //needs to be set in any case, also plain decomp enters here
			sty lz_offset_lo + 1           //initialize offset with $0000
			sty lz_offset_hi + 1
			// The branch operand is assembled for the initial distance-1 state,
			// matching the raw-high decoder, so no runtime patch is needed here.
			// Keep this last: INPLACE startup relies on Z being set.
			sty lz_len_hi

#else
			ldy #$00
			sty lz_bits
			sty lz_bit_byte
			sty lz_len_hi
			lda #$01
			sta raw_offset_lo + 1
			sty raw_offset_hi + 1

			jsr raw_read_elias_le
			jsr raw_copy_literals
#if INPLACE
			jsr raw_is_end
			bcc !+
			jmp raw_done
!:
#endif

raw_after_literal:
			jsr raw_read_bit
			bcs raw_new_offset
			jsr raw_read_elias_le
			jsr raw_copy_match
#if INPLACE
			jsr raw_is_end
			bcc !+
			jmp raw_done
!:
#endif

raw_after_match:
			jsr raw_read_bit
			bcs raw_new_offset
			jsr raw_read_elias_le
			jsr raw_copy_literals
#if INPLACE
			jsr raw_is_end
			bcc !+
			jmp raw_done
!:
#endif
			jmp raw_after_literal

raw_new_offset:
			jsr raw_read_bit
			bcs raw_long_offset

			jsr raw_read_4_bits
			clc
			adc #$01
			sta raw_offset_lo + 1
			lda #$00
			sta raw_offset_hi + 1
			jsr raw_read_elias_le
			jsr raw_inc_len
			jsr raw_copy_match
#if INPLACE
			jsr raw_is_end
			bcc !+
			jmp raw_done
!:
#endif
			jmp raw_after_match

raw_long_offset:
			jsr raw_read_elias
			lda lz_len_lo
			beq raw_done
			sec
			sbc #$01
			sta lz_len_hi

			jsr raw_read_byte
			pha
			lsr
			sta lz_len_lo

			lda lz_len_hi
			lsr
			sta raw_offset_hi + 1
			lda lz_len_hi
			and #$01
			beq !+
			lda #$80
			bne !++
!:
			lda #$00
!:
			clc
			adc lz_len_lo
			adc #$01
			sta raw_offset_lo + 1
			bcc !+
			inc raw_offset_hi + 1
!:
			pla
			and #$01
			bne !+
			jsr raw_read_elias_le_skip
			jsr raw_inc_len
			jmp !++
!:
			lda #$02
			sta lz_len_lo
			lda #$00
			sta lz_len_hi
!:
			jsr raw_copy_match
#if INPLACE
			jsr raw_is_end
			bcc !+
			jmp raw_done
!:
#endif
			jmp raw_after_match

raw_done:
			rts

#if INPLACE
raw_is_end:
			lda lz_dst + 0
			cmp lz_src + 0
			bne !+
			lda lz_dst + 1
			cmp lz_src + 1
			beq !done+
!:
			clc
			rts
!done:
			sec
			rts
#endif

raw_read_byte:
			lda (lz_src),y
			inc lz_src + 0
			bne !+
			inc lz_src + 1
!:
			rts

raw_read_bit:
			lsr lz_bits
			bne !+
			lda #$80
			sta lz_bits
			jsr raw_read_byte
			sta lz_bit_byte
!:
			lda lz_bit_byte
			and lz_bits
			beq !+
			sec
			rts
!:
			clc
			rts

raw_read_4_bits:
			lda #$00
			sta lz_len_lo
			ldx #$04
!:
			jsr raw_read_bit
			rol lz_len_lo
			dex
			bne !-
			lda lz_len_lo
			rts

raw_read_elias_le:
			jsr raw_read_elias
			jmp raw_elias_to_le

raw_read_elias_le_skip:
			jsr raw_read_elias_skip
			jmp raw_elias_to_le

raw_read_elias:
			lda #$01
			sta lz_len_lo
			lda #$00
			sta lz_len_hi
!:
			jsr raw_read_bit
			bcs !done+
raw_elias_payload:
			asl lz_len_lo
			rol lz_len_hi
			jsr raw_read_bit
			bcc !+
			inc lz_len_lo
			bne !+
			inc lz_len_hi
!:
			jmp !--
!done:
			rts

raw_read_elias_skip:
			lda #$01
			sta lz_len_lo
			lda #$00
			sta lz_len_hi
			jmp raw_elias_payload

raw_elias_to_le:
			lda lz_len_hi
			bne !+
			rts
!:
			ldx #$07
			cmp #$80
			bcs raw_elias_le_known_bit
			dex
			cmp #$40
			bcs raw_elias_le_known_bit
			dex
			cmp #$20
			bcs raw_elias_le_known_bit
			dex
			cmp #$10
			bcs raw_elias_le_known_bit
			dex
			cmp #$08
			bcs raw_elias_le_known_bit
			dex
			cmp #$04
			bcs raw_elias_le_known_bit
			dex
			cmp #$02
			bcs raw_elias_le_known_bit
			dex
raw_elias_le_known_bit:
			lda raw_low_masks,x
			and lz_len_lo
			pha
			lda raw_bit_masks,x
			pha
			eor #$ff
			and lz_len_hi
			sta lz_len_hi
			cpx #$00
			beq !shift_done+
!shift:
			lsr lz_len_hi
			ror lz_len_lo
			dex
			bne !shift-
!shift_done:
			pla
			sta lz_len_hi
			pla
			ora lz_len_hi
			sta lz_len_hi
			rts

raw_inc_len:
			inc lz_len_lo
			bne !+
			inc lz_len_hi
!:
			rts

raw_dec_len:
			lda lz_len_lo
			bne !+
			dec lz_len_hi
!:
			dec lz_len_lo
			lda lz_len_lo
			ora lz_len_hi
			rts

raw_copy_literals:
			lda lz_len_lo
			ora lz_len_hi
			beq !done+
!:
			jsr raw_read_byte
			sta (lz_dst),y
			inc lz_dst + 0
			bne !+
			inc lz_dst + 1
!:
			jsr raw_dec_len
			bne !--
!done:
			rts

raw_copy_match:
			lda lz_dst + 0
			sec
raw_offset_lo:	sbc #$00
			sta raw_match_src + 1
			lda lz_dst + 1
raw_offset_hi:	sbc #$00
			sta raw_match_src + 2
			lda lz_len_lo
			ora lz_len_hi
			beq !done+
!:
raw_match_src:	lda $beef
			sta (lz_dst),y
			inc raw_match_src + 1
			bne !+
			inc raw_match_src + 2
!:
			inc lz_dst + 0
			bne !+
			inc lz_dst + 1
!:
			jsr raw_dec_len
			bne !---
!done:
			rts

raw_bit_masks:
			.byte $01,$02,$04,$08,$10,$20,$40,$80
raw_low_masks:
			.byte $00,$01,$03,$07,$0f,$1f,$3f,$7f
#endif

	#else
	decrunch:

			stx lz_src + 1
			sty lz_src + 0

                        ldy #$00                        //needs to be set in any case, also plain decomp enters here
                        ldx #$02
			:init_lz_bits()
!:
                        lda (lz_src),y
                        sta lz_dst + 0 - 1, x
                        inc lz_src + 0
                        bne !skp+
                        inc lz_src + 1
					!skp:
                        dex
                        bne !-
			stx lz_offset_lo + 1           //initialize offset with $0000
			stx lz_offset_hi + 1
			lda #MATCH_LOOP_RLE
			sta lz_match_loop + 1
			// Keep this last: INPLACE startup relies on Z being set.
			stx lz_len_hi
	#endif

#if !ZX0RAW || ZX0RAW_FAST
#if INPLACE

			beq lz_start_over
lz_end_check_:
			ldx lz_dst + 0			//check for end condition when depacking inplace, lz_dst + 0 still in X
			cpx lz_src + 0
			bne lz_start_over
lz_eof:
			rts				//if lz_src + 1 gets incremented, the barrier check hits in even later, so at least one block is loaded, if it was $ff, we at least load the last block @ $ffxx, it must be the last block being loaded anyway
lz_end_check:
			cpx lz_src + 1
			beq lz_end_check_		//we could check against src >= dst XXX TODO
#else

	lz_end_check:

#endif
lz_start_over:
			lda #$01			//we fall through this check on entry and start with literal
			:get_lz_bit()
			beq lz_start_refill
lz_start_bits_ready:
			bcc lz_literal
			jmp lz_match			//after each match check for another match or literal?

lz_start_refill:
			jsr lz_refill_control
			jmp lz_start_bits_ready

			//------------------
			//LITERAL
			//------------------
#if KCRUNCH_FAST_LITERALS
			// Runs of at least eight bytes amortize pointer setup. Advance
			// both bases, index backward from the next page, and patch
			// absolute indexed operands to save two cycles per copied byte.
lz_cp_lit_fast_setup:
			txa
			clc
			adc lz_src + 0
			sta lz_src + 0
			bcc !+
			inc lz_src + 1
!:
			txa
			clc
			adc lz_dst + 0
			sta lz_dst + 0
			bcc !+
			inc lz_dst + 1
!:
			dec lz_src + 1
			dec lz_dst + 1
			txa
			eor #$ff
			tay
			iny
lz_cp_lit_fast_patch:
			lda lz_src + 0
			sta lz_fast_src + 0
			lda lz_src + 1
			sta lz_fast_src + 1
			lda lz_dst + 0
			sta lz_fast_dst + 0
			lda lz_dst + 1
			sta lz_fast_dst + 1
lz_cp_lit_fast:
.label lz_fast_src = * + 1
			lda $beef,y
.label lz_fast_dst = * + 1
			sta $beef,y
			iny
			bne lz_cp_lit_fast
			inc lz_src + 1
			inc lz_dst + 1
			ldx #$00
			sec
			jmp lz_cp_lit_done
#endif

lz_literal:
			:get_lz_bit()
			bcs !skp+
!:							//lz_length as inline
			:get_lz_bit()			//fetch payload bit
			rol				//can also moved to front and executed once on start
			:get_lz_bit()
			bcc !-
!skp:
			bne !skp+
			jsr lz_refill_bits
			//:_lz_refill_bits()
!skp:
			tax
lz_l_page_:
#if KCRUNCH_FAST_LITERALS
			and #$f8
			bne lz_cp_lit_fast_setup
#endif
lz_cp_lit:
			lda (lz_src),y			///!\ Need to copy this way, or we run into danger to copy from an area that is yet blocked by barrier, this totally sucks, loading in order reveals that
			sta (lz_dst),y

			inc lz_src + 0
			beq lz_inc_src3
lz_inc_src3_:
			inc lz_dst + 0
			beq lz_dst_inc
lz_dst_inc_:
			dex
			bne lz_cp_lit

lz_cp_lit_done:
			lda lz_len_hi			//more pages to copy?
			beq !+
			jmp lz_l_page			//happens very seldom
!:
lz_literal_complete:

			//------------------
			//NEW OR OLD OFFSET
			//------------------
							//in case of type bit == 0 we can always receive length (not length - 1), can this used for an optimization? can we fetch length beforehand? and then fetch offset? would make length fetch simpler? place some other bit with offset?
			rol				//was A = 0, C = 1 -> A = 1 with rol, but not if we copy literal this way
			:get_lz_bit()
			bcc lz_repeat			//either match with new offset or old offset

			jmp lz_match

lz_inc_src3:
			inc lz_src + 1
			jmp lz_inc_src3_
lz_dst_inc:
			inc lz_dst + 1
			jmp lz_dst_inc_

			//------------------
			//REPEAT LAST OFFSET
			//------------------
lz_repeat:
			:get_lz_bit()			//cheaper with 2 branches, as initial branch to lz_literal therefore is removed
			bcs !skp+
!:
			:get_lz_bit()			//fetch payload bit
			rol				//can also moved to front and executed once on start
			:get_lz_bit()			//cheaper with 2 branches, as initial branch to lz_literal therefore is removed
			bcc !-
!skp:
			bne !skp+
			jsr lz_refill_bits		//fetch more bits
!skp:
			sbc #$01			//subtract 1, will be added again on adc as C = 1
			sec
lz_match_big:						//we enter with length - 1 here from normal match
			eor #$ff
			tay
lz_m_page_:
			eor #$ff			//restore A
lz_match_len2:						//entry from new_offset handling
			adc lz_dst + 0
			sta lz_dst + 0
			sta lz_mdst + 0
			bcc !+
			jmp lz_clc			///!\ branch happens very seldom, if so, clear carry
!:
			dec lz_dst + 1			//subtract one more in this case
lz_clc_back:
lz_offset_lo:		sbc #$00			//carry is cleared, subtract (offset + 1) in fact we could use sbx here, but would not respect carry, but a and x are same, but need x later anyway for other purpose
			sta lz_msrcr + 0
			lax lz_dst + 1
			stx lz_mdst + 1
lz_offset_hi:		sbc #$00
			sta lz_msrcr + 1
lz_cp_match:
.label lz_msrcr = * + 1
			lda $beef,y
lz_cp_store:
.label lz_mdst = * + 1
			sta $beef,y
			iny
	lz_match_loop:
			bne lz_cp_store		//initial last offset is distance 1
			inx
			stx lz_dst + 1			//cheaper to get lz_dst + 1 into x than lz_dst + 0 for upcoming compare

			lda lz_len_hi			//check for more loop runs
			bne !+
			jmp lz_end_check		//do more page runs? Yes? Fall through
!:
			dec lz_len_hi
			inc lz_msrcr + 1
			inc lz_mdst + 1
			jmp lz_cp_match

lz_l_page:
			dec lz_len_hi
#if KCRUNCH_FAST_LITERALS
			jmp lz_cp_lit_fast_patch
#else
			jmp lz_l_page_
#endif


			//------------------
			//SELDOM STUFF
			//------------------
			//------------------
			//MATCH
			//------------------
!:							//lz_length as inline
			:get_lz_bit()			//fetch payload bit
			rol				//can also moved to front and executed once on start
lz_match:
			:get_lz_bit()
			bcc !-

			bne !skp+
			jsr lz_refill_bits
			beq lz_lend			//underflow, so offset was $100
!skp:
			sbc #$01			//subtract 1, elias numbers range from 1..256, we need 0..255

			lsr				//set bit 15 to 0 while shifting hibyte
			sta lz_offset_hi + 1		//hibyte of offset

			lda (lz_src),y			//fetch another byte directly, same as refill_bits...
			ror				//and shift -> first bit for lenth is in carry, and we have %0xxxxxxx xxxxxxxx as offset
			sta lz_offset_lo + 1

			// Dali stores distance - 1, so encoded offset zero is distance 1.
			// Select a store-only loop for that case; preserve the embedded
			// first match-length bit held in carry.
			ldx #MATCH_LOOP_NORMAL
			ora lz_offset_hi + 1
			bne !set_match_loop+
			ldx #MATCH_LOOP_RLE
!set_match_loop:
			stx lz_match_loop + 1

			inc lz_src + 0			//postponed, so no need to save A on next_page call
			beq lz_inc_src1
lz_inc_src1_:
			lda #$01
			ldy #$fe
			bcs lz_match_len2		//length = 1 ^ $ff, do it the very short way :-)
lz_match_len:
!:
			:get_lz_bit()
			rol
			:get_lz_bit()
			bcc !-
			bne lz_match_big
			ldy #$00			//only now y = 0 is needed
			jsr lz_refill_bits		//fetch remaining bits
			beq !+
			jmp lz_match_big
!:
			inc lz_len_hi
			bcc !+
			jmp lz_match_big		//and enter match copy loop
!:

			//------------------
			//SELDOM STUFF
			//------------------
lz_clc:
			clc
			jmp lz_clc_back
lz_inc_src1:
			inc lz_src + 1			//preserves carry, all sane
			bne lz_inc_src1_
lz_inc_src2:
			inc lz_src + 1			//preserves carry and A, clears X, Y, all sane
			bne lz_inc_src2_

lz_refill_control:
			pha
			lda (lz_src),y
			:set_lz_bit_marker()
			sta lz_bits
			inc lz_src + 0
			beq lz_refill_control_inc
lz_refill_control_done:
			pla
			rts

lz_refill_control_inc:
			inc lz_src + 1
			bne lz_refill_control_done

			//------------------
			//ELIAS FETCH
			//------------------
lz_refill_bits:
			tax
			lda (lz_src),y
			:set_lz_bit_marker()
			sta lz_bits
			inc lz_src + 0 		//postponed, so no need to save A on next_page call
			beq lz_inc_src2
lz_inc_src2_:
			txa				//also postpone, so A can be trashed on lz_inc_src above
			bcs lz_lend
lz_get_loop:
			:get_lz_bit()			//fetch payload bit
lz_length_16_:
			rol				//can also moved to front and executed once on start
			bcs lz_length_16		//first 1 drops out from lowbyte, need to extend to 16 bit, unfortunatedly this does not work with inverted numbers
			:get_lz_bit()
			bcc lz_get_loop
			beq lz_refill_bits
lz_lend:
			rts
lz_length_16:						//happens very rarely
			pha				//save LSB
			tya				//was lda #$01, but A = 0 + upcoming rol makes this also start with MSB = 1
			jsr lz_length_16_		//get up to 7 more bits
			sta lz_len_hi			//save MSB
			pla				//restore LSB
			bne !skp+
			dec lz_len_hi
			tya
!skp:
			rts
#endif
}

/*
.macro _lz_refill_bits()
{
lz_refill_bits:
			tax
			lda (lz_src),y
			:set_lz_bit_marker()
			sta lz_bits
			inc lz_src + 0 		//postponed, so no need to save A on next_page call
			bne lz_inc_src2_
			inc lz_src + 1
lz_inc_src2_:
			txa				//also postpone, so A can be trashed on lz_inc_src above
			bcs lz_lend
lz_get_loop:
			:get_lz_bit()			//fetch payload bit
lz_length_16_:
			rol				//can also moved to front and executed once on start
			bcs lz_length_16		//first 1 drops out from lowbyte, need to extend to 16 bit, unfortunatedly this does not work with inverted numbers
			:get_lz_bit()
			bcc lz_get_loop
			beq lz_refill_bits
lz_lend:
			bne !skp+
lz_length_16:						//happens very rarely
			pha				//save LSB
			tya				//was lda #$01, but A = 0 + upcoming rol makes this also start with MSB = 1
			jsr lz_length_16_		//get up to 7 more bits
			sta lz_len_hi			//save MSB
			pla				//restore LSB
			bne !skp+
			dec lz_len_hi
			tya
!skp:
			//rts
}
*/
