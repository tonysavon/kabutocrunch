/*
 * decode.c - strict C decoders for Kabuto raw-high and classic Dali streams
 */
#include <stdint.h>
#include "decode.h"
#include "kc_format.h"

typedef struct dc {
   const unsigned char *src;
   int src_pos;
   int src_size;
   unsigned char *dst;
   int dst_pos;
   int dst_size;
   int bit_mask;
   int bit_byte;
   const char *error;
} dc;

static int dc_fail(dc *decoder, const char *message) {
   if (decoder->error == NULL)
      decoder->error = message;
   return 0;
}

static int dc_read_byte(dc *decoder, unsigned int *value) {
   if (decoder->src_pos >= decoder->src_size)
      return dc_fail(decoder, "truncated compressed stream");
   *value = decoder->src[decoder->src_pos++];
   return 1;
}

static int dc_read_bit(void *context, unsigned int *value) {
   dc *decoder = (dc *)context;
   unsigned int byte;

   if ((decoder->bit_mask >>= 1) == 0) {
      decoder->bit_mask = 0x80;
      if (!dc_read_byte(decoder, &byte))
         return 0;
      decoder->bit_byte = (int)byte;
   }
   *value = (decoder->bit_byte & decoder->bit_mask) != 0;
   return 1;
}

static int dc_read_length(dc *decoder, int first_zero_consumed,
      uint32_t *value) {
   int ok = kc_read_dali_length(first_zero_consumed, dc_read_bit, decoder,
      value);
   if (!ok) {
      if (decoder->error == NULL)
         decoder->error = "invalid or overflowing length code";
      return 0;
   }
   if (*value == 0 || *value > MAX_VARLEN)
      return dc_fail(decoder, "length is outside the supported range");
   return 1;
}

static int dc_copy_literals(dc *decoder, uint32_t length) {
   uint32_t i;
   unsigned int byte;

   if (length > (uint32_t)(decoder->dst_size - decoder->dst_pos))
      return dc_fail(decoder, "literal run exceeds output capacity");
   if (length > (uint32_t)(decoder->src_size - decoder->src_pos))
      return dc_fail(decoder, "truncated literal run");
   for (i = 0; i < length; i++) {
      if (!dc_read_byte(decoder, &byte))
         return 0;
      decoder->dst[decoder->dst_pos++] = (unsigned char)byte;
   }
   return 1;
}

static int dc_copy_match(dc *decoder, int offset, uint32_t length) {
   uint32_t i;
   int source;

   if (offset < MIN_OFFSET || offset > MAX_OFFSET || offset > decoder->dst_pos)
      return dc_fail(decoder, "invalid match offset");
   if (length > (uint32_t)(decoder->dst_size - decoder->dst_pos))
      return dc_fail(decoder, "match exceeds output capacity");

   source = decoder->dst_pos - offset;
   for (i = 0; i < length; i++)
      decoder->dst[decoder->dst_pos++] = decoder->dst[source + (int)i];
   return 1;
}

static int kc_decode_format(const unsigned char *packed, int packed_size,
      unsigned char *output, int output_cap, int *out_size,
      const char **error_message, int dali_mode) {
   dc decoder;
   int state = 0;
   int last_offset = 1;

   if (out_size != NULL)
      *out_size = 0;
   if (error_message != NULL)
      *error_message = NULL;
   if (packed == NULL || packed_size <= 0 || output == NULL ||
       output_cap < 0 || out_size == NULL) {
      if (error_message != NULL)
         *error_message = "invalid decoder arguments or empty stream";
      return 0;
   }

   decoder.src = packed;
   decoder.src_pos = 0;
   decoder.src_size = packed_size;
   decoder.dst = output;
   decoder.dst_pos = 0;
   decoder.dst_size = output_cap;
   decoder.bit_mask = 0;
   decoder.bit_byte = 0;
   decoder.error = NULL;

   for (;;) {
      unsigned int command;
      uint32_t value;

      if (state == 0 || state == 2) {
         if (state == 2) {
            if (!dc_read_bit(&decoder, &command))
               break;
            if (command != 0)
               goto new_offset;
         }
         if (!dc_read_length(&decoder, 0, &value) ||
             !dc_copy_literals(&decoder, value))
            break;
         state = 1;
      }
      else {
         if (!dc_read_bit(&decoder, &command))
            break;
         if (command != 0)
            goto new_offset;
         if (!dc_read_length(&decoder, 0, &value) ||
             !dc_copy_match(&decoder, last_offset, value))
            break;
         state = 2;
      }
      continue;

new_offset:
      {
         unsigned int offset_high;
         unsigned int short_offset;
         unsigned int metadata;
         uint32_t dali_offset_high;
         uint32_t length_value;
         int offset;

         if (dali_mode) {
            if (!kc_read_dali_length(0, dc_read_bit, &decoder,
                  &dali_offset_high)) {
               if (decoder.error == NULL)
                  decoder.error = "invalid or truncated Dali offset code";
               break;
            }
            if (dali_offset_high == 256) {
               *out_size = decoder.dst_pos;
               if (error_message != NULL)
                  *error_message = NULL;
               return 1;
            }
            if (dali_offset_high == 0 || dali_offset_high > 255) {
               dc_fail(&decoder, "invalid Dali offset high value");
               break;
            }
            offset_high = (unsigned int)dali_offset_high - 1;
         }
         else {
            if (!dc_read_bit(&decoder, &short_offset))
               break;
            if (short_offset) {
               offset_high = 0;
            }
            else if (!dc_read_byte(&decoder, &offset_high)) {
               break;
            }
            else if (offset_high == 0) {
               *out_size = decoder.dst_pos;
               if (error_message != NULL)
                  *error_message = NULL;
               return 1;
            }
         }
         if (!dc_read_byte(&decoder, &metadata)) {
            if (decoder.error == NULL)
               decoder.error = "invalid offset high byte";
            break;
         }

         offset = (int)offset_high * 128 + (int)(metadata >> 1) + 1;
         if ((metadata & 1U) != 0) {
            length_value = 1;
         }
         else if (!dc_read_length(&decoder, 1, &length_value)) {
            break;
         }
         if (length_value >= MAX_VARLEN ||
             !dc_copy_match(&decoder, offset, length_value + 1))
            break;
         last_offset = offset;
         state = 2;
      }
   }

   *out_size = decoder.dst_pos;
   if (error_message != NULL)
      *error_message = decoder.error != NULL ? decoder.error : "malformed stream";
   return 0;
}

int kc_decode(const unsigned char *packed, int packed_size,
      unsigned char *output, int output_cap, int *out_size,
      const char **error_message) {
   return kc_decode_format(packed, packed_size, output, output_cap, out_size,
      error_message, 0);
}

int kc_decode_dali(const unsigned char *packed, int packed_size,
      unsigned char *output, int output_cap, int *out_size,
      const char **error_message) {
   return kc_decode_format(packed, packed_size, output, output_cap, out_size,
      error_message, 1);
}
