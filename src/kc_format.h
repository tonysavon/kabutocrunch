/* Kabuto stream constants and Dali-derived Elias length helpers. */
#ifndef KC_FORMAT_H
#define KC_FORMAT_H

#include <stdint.h>

#define MIN_OFFSET 1
#define MAX_OFFSET 0x7f80
#define MAX_VARLEN 0xffff
#define BLOCK_SIZE 0x10000
#define MIN_MATCH_SIZE 1

typedef int (*kc_put_bit_fn)(void *context, unsigned int bit);
typedef int (*kc_get_bit_fn)(void *context, unsigned int *bit);

unsigned int kc_dali_length_bit_cost(uint32_t value);
int kc_write_dali_length(uint32_t value, int skip_first_bit,
      kc_put_bit_fn put_bit, void *context);
int kc_read_dali_length(int first_zero_consumed, kc_get_bit_fn get_bit,
      void *context, uint32_t *value);

#endif /* KC_FORMAT_H */
