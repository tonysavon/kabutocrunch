#ifndef KC_DECODE_H
#define KC_DECODE_H

int kc_decode(const unsigned char *packed, int packed_size,
      unsigned char *output, int output_cap, int *out_size,
      const char **error_message);

int kc_decode_dali(const unsigned char *packed, int packed_size,
      unsigned char *output, int output_cap, int *out_size,
      const char **error_message);

#endif /* KC_DECODE_H */
