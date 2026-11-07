// Define KCRUNCH_LEGAL_OPCODES before importing for documented 6502 instructions.
// Fast decoder for the default KabutoCrunch bitstream.
//#define KABUTORAW
// Comment out for a smaller decoder that keeps the other hot-path wins.
#define KCRUNCH_FAST_LITERALS

#if KABUTORAW
.macro KABUTO_RAWDECRUNCH(src,dst)
{
	ldy #<src
	ldx #>src
	
	lda #<dst
	sta.zp lz_dst
	lda #>dst 
	sta.zp lz_dst + 1
	
	jsr kabuto.rawdecrunch
}
#else
.macro KABUTO_DECRUNCH(addr)
{
	ldy #<addr
    ldx #>addr
    jsr kabuto.decrunch
}
#endif

//#define INPLACE

.label CONFIG_ZP_ADDR	= $f8
.const LZ_BITS_LEFT     = 1

.label lz_bits			= CONFIG_ZP_ADDR + 0
.label lz_dst			= CONFIG_ZP_ADDR + 1
.label lz_src			= CONFIG_ZP_ADDR + 3
#if KABUTORAW
.label lz_len_hi		= CONFIG_ZP_ADDR + 6
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
kabuto:
{
	.const MATCH_LOOP_NORMAL = (lz_cp_match - (lz_match_loop + 2)) & $ff
	.const MATCH_LOOP_RLE    = (lz_cp_store - (lz_match_loop + 2)) & $ff

	#if KABUTORAW
	rawdecrunch:
			stx lz_src + 1
			sty lz_src + 0
			ldx #$02
			:init_lz_bits()
			ldy #$00                        //needs to be set in any case, also plain decomp enters here
			sty lz_offset_lo + 1           //initialize offset with $0000
			sty lz_offset_hi + 1
			// The branch operand is assembled for the initial distance-1 state.
			sty lz_len_hi


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
			stx lz_len_hi
	#endif
	
#if INPLACE || KCRUNCH_FAST_LITERALS
			jmp lz_start_over
#endif

#if KCRUNCH_FAST_LITERALS
			// For runs of six bytes or more, bias both pointers by
			// length - 256 and start Y at -length. Copy forward in pairs
			// so overlapping input is read before it can be overwritten.
lz_cp_lit_fast_setup:
			txa
			clc
			adc lz_src + 0
			sta lz_src + 0
			lda #$ff
			adc lz_src + 1
			sta lz_src + 1

			txa
			clc
			adc lz_dst + 0
			sta lz_dst + 0
			lda #$ff
			adc lz_dst + 1
			sta lz_dst + 1

			txa
			eor #$ff
			tay
			iny
lz_cp_lit_fast_patch:
			lda lz_dst + 0
			sta lz_fast_dst + 0
			sta lz_fast_dst_tail + 0
			lda lz_dst + 1
			sta lz_fast_dst + 1
			sta lz_fast_dst_tail + 1
			tya
			lsr
			bcs lz_cp_lit_fast_tail		// Odd count: enter on the second byte.
lz_cp_lit_fast:
			lda (lz_src),y
.label lz_fast_dst = * + 1
			sta $beef,y
			iny
lz_cp_lit_fast_tail:
			lda (lz_src),y
.label lz_fast_dst_tail = * + 1
			sta $beef,y
			iny
			bne lz_cp_lit_fast
			inc lz_src + 1
			inc lz_dst + 1
			jmp lz_cp_lit_done
#endif

#if INPLACE
lz_end_check_:
			ldx lz_dst + 0
			cpx lz_src + 0
			bne lz_start_over
lz_eof:
			rts
#endif
lz_end_check:
#if INPLACE
			cpx lz_src + 1
			beq lz_end_check_
#endif
lz_start_over:
			// The first literal length uses an odd number of control bits.
			// Subsequent tokens use an even number, including the command:
			// a literal/repeat adds odd length bits, and a new match adds an
			// offset selector plus even length bits (its first bit is embedded).
			// The next command is therefore even-numbered and cannot refill.
			:get_lz_bit()
			bcs lz_match
lz_literal:
			lda #$01
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
			cpx #$06
			bcs lz_cp_lit_fast_setup
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
			bne lz_l_page
			// The command after a literal is also at an even bit position.
			:get_lz_bit()
			bcc lz_repeat

lz_match:
			:get_lz_bit()
			bne lz_offset_selector_ready
			// The offset selector is the first bit of this control byte.
			lda (lz_src),y
			:set_lz_bit_marker()
			sta lz_bits
			inc lz_src + 0
			bne lz_offset_selector_ready
			inc lz_src + 1
lz_offset_selector_ready:
			bcc lz_long_offset
#if KCRUNCH_LEGAL_OPCODES
            lda #$00
            clc
#else
			anc #$00			//A = 0, C = 0: high-group low bit for metadata ROR
#endif
			sta lz_offset_hi + 1
lz_offset_metadata:
			ldx #MATCH_LOOP_NORMAL
			lda (lz_src),y
			ror				// Carry in is the low bit of the high group.
							// Carry out is the first match-length bit.
			sta lz_offset_lo + 1

			// The stream stores distance - 1, so encoded offset zero is distance 1.
			// Select a store-only loop for that case; preserve the embedded
			// first match-length bit held in carry.
			bne !set_match_loop+
			lda lz_offset_hi + 1
			bne !set_match_loop+
			ldx #MATCH_LOOP_RLE
!set_match_loop:
			stx lz_match_loop + 1

			inc lz_src + 0			//postponed, so no need to save A on next_page call
			beq lz_inc_src1
lz_inc_src1_:
			lda #$01
			bcs lz_short_match
			// Y is still zero for any length refill.
!:
			:get_lz_bit()
			rol
			:get_lz_bit()
			bcc !-
			bne lz_match_big
			jsr lz_refill_bits
			bne lz_match_big
			// The refill stores full-page lengths with the high byte biased
			// down by one. Undo that bias before the new-offset length adds one.
			inc lz_len_hi
			jmp lz_match_big
lz_short_match:
			ldy #$fe			// Start the two-byte copy at Y = -2.
			bne lz_match_len2

lz_long_offset:
			// Long offsets store their 7-bit-group high part directly. Zero is
			// reserved for end-of-stream, so offset decoding has no Elias loop.
			lda (lz_src),y
			beq lz_long_offset_end
			lsr
			sta lz_offset_hi + 1
			inc lz_src + 0
			bne lz_offset_metadata
			inc lz_src + 1
			jmp lz_offset_metadata
lz_long_offset_end:
			rts

lz_l_page:
			dec lz_len_hi
#if KCRUNCH_FAST_LITERALS
			jmp lz_cp_lit_fast_patch
#else
			jmp lz_l_page_
#endif

lz_inc_src1:
			inc lz_src + 1
			jmp lz_inc_src1_
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
			lda #$01
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
			bcs lz_clc			// A low-byte carry cancels the page bias.
			dec lz_dst + 1
lz_clc_back:
lz_offset_lo:		sbc #$00			//carry is cleared, subtract (offset + 1) in fact we could use sbx here, but would not respect carry, but a and x are same, but need x later anyway for other purpose
			sta lz_msrcr + 0
#if KCRUNCH_LEGAL_OPCODES
            lda lz_dst + 1
            tax
#else
			lax lz_dst + 1
#endif
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
			bne lz_cp_store			//initial last offset is distance 1
			inx
			stx lz_dst + 1			//cheaper to get lz_dst + 1 into x than lz_dst + 0 for upcoming compare

			lda lz_len_hi			//check for more loop runs
			bne !+
			jmp lz_end_check
!:
			dec lz_len_hi
			inc lz_msrcr + 1
			inc lz_mdst + 1
			jmp lz_cp_match


			//------------------
			//SELDOM STUFF
			//------------------
lz_clc:
			clc
			jmp lz_clc_back
lz_inc_src2:
			inc lz_src + 1			//preserves carry and A, clears X, Y, all sane
			bne lz_inc_src2_

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
