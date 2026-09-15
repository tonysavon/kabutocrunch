/* Dali's positive-integer interlaced Elias representation. */
#include <limits.h>
#include "kc_format.h"

static unsigned int kc_floor_log2(uint32_t value) {
#ifdef __GNUC__
   return 31U - (unsigned int)__builtin_clz(value);
#else
   unsigned int result = 0;
   while (value >>= 1)
      result++;
   return result;
#endif
}

unsigned int kc_dali_length_bit_cost(uint32_t value) {
   return value == 0 ? 0 : (kc_floor_log2(value) << 1) + 1;
}

typedef struct kc_length_writer {
   kc_put_bit_fn put_bit;
   void *context;
   int skip;
} kc_length_writer;

static int kc_emit_bit(kc_length_writer *writer, unsigned int bit) {
   if (writer->skip) {
      writer->skip = 0;
      return 1;
   }
   return writer->put_bit(writer->context, bit & 1U);
}

int kc_write_dali_length(uint32_t value, int skip_first_bit,
      kc_put_bit_fn put_bit, void *context) {
   kc_length_writer writer;
   unsigned int bits;
   uint32_t marker;

   if (value == 0 || put_bit == NULL)
      return 0;

   writer.put_bit = put_bit;
   writer.context = context;
   writer.skip = skip_first_bit != 0;
   bits = kc_floor_log2(value);
   marker = UINT32_C(1) << bits;
   if (bits >= 8) {
      value &= ~marker;
      value = (value >> 8) | ((value & UINT32_C(0xff)) << (bits - 8));
   }
   while ((marker >>= 1) != 0) {
      if (!kc_emit_bit(&writer, 0) ||
          !kc_emit_bit(&writer, (value & marker) != 0))
         return 0;
   }
   return kc_emit_bit(&writer, 1);
}

static int kc_read_one(kc_get_bit_fn get_bit, void *context, unsigned int *bit) {
   return get_bit != NULL && get_bit(context, bit);
}

int kc_read_dali_length(int first_zero_consumed, kc_get_bit_fn get_bit,
      void *context, uint32_t *result) {
   uint32_t value = 1;
   unsigned int control;
   unsigned int payload;
   unsigned int bits;

   if (get_bit == NULL || result == NULL)
      return 0;
   if (first_zero_consumed) {
      control = 0;
   }
   else if (!kc_read_one(get_bit, context, &control)) {
      return 0;
   }
   while (control == 0) {
      if (!kc_read_one(get_bit, context, &payload) || value > (UINT32_MAX >> 1))
         return 0;
      value = (value << 1) | payload;
      if (!kc_read_one(get_bit, context, &control))
         return 0;
   }
   bits = kc_floor_log2(value);
   if (bits >= 8) {
      uint32_t payload_mask = (UINT32_C(1) << bits) - 1;
      uint32_t encoded = value & payload_mask;
      uint32_t low_eight = encoded >> (bits - 8);
      uint32_t rest_mask = (UINT32_C(1) << (bits - 8)) - 1;
      uint32_t rest = encoded & rest_mask;
      value = (UINT32_C(1) << bits) | (rest << 8) | low_eight;
   }
   *result = value;
   return 1;
}
