#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sfx.h"
#include "kc_lib.h"
#include "kc_format.h"
#include "decode.h"

#define BUFFER_SIZE 65536
#define PACKED_BUFFER_SIZE (BUFFER_SIZE + 256)
#define INITIAL_OFFSET 1

#define FALSE 0
#define TRUE 1
#define DALI_ELIAS_LE 1
#define DALI_VARS_SIZE 18

typedef struct ctx {
    unsigned char *packed_data;
    unsigned char *unpacked_data;
    size_t packed_index;
    size_t packed_size;
    int packed_bit_mask;
    int packed_bit_value;
    size_t unpacked_index;
    size_t unpacked_size;
    int inplace;

    char *output_name;
    char *input_name;
    char *prefix_name;
    char *verify_packed_name;

    int cbm;
    int cbm_orig_addr;
    int cbm_packed_addr;
    int cbm_range_from;
    int cbm_range_to;
    int cbm_relocate_packed_addr;
    int cbm_relocate_origin_addr;
    int cbm_relocate_sfx_addr;
    int cbm_prefix_from;

    int sfx;
    int sfx_addr;
    int sfx_01;
    int sfx_cli;
    int sfx_small;
    int sfx_effect;
    int sfx_size;
    unsigned char *sfx_code;

    int exit_on_warn;
    int show_stats;
    int max_match_len;
    int speed_bias;
    int dali;
    int short_runs;
    kc_search_config search_config;
} ctx;

static const char *stream_name(const ctx *ctx) {
    return ctx->dali ? "Dali" : "Kabuto raw-high";
}

static int decode_stream(const ctx *ctx, const unsigned char *packed,
        int packed_size, unsigned char *output, int output_cap,
        int *out_size, const char **error_message) {
    if (ctx->dali) {
        return kc_decode_dali(packed, packed_size, output, output_cap,
            out_size, error_message);
    }
    return kc_decode(packed, packed_size, output, output_cap,
        out_size, error_message);
}

static int read_number(char* arg, char* argname, int limit) {
    const char *digits = arg;
    int base = 10;
    char *end;
    unsigned long number;

    if (arg == NULL || *arg == '\0') {
        fprintf(stderr, "Error: missing value for %s\n", argname);
        exit(1);
    }
    if (arg[0] == '$') {
        digits = arg + 1;
        base = 16;
    }
    else if (arg[0] == '0' && (arg[1] == 'x' || arg[1] == 'X')) {
        digits = arg + 2;
        base = 16;
    }
    errno = 0;
    number = strtoul(digits, &end, base);
    if (digits == end || *end != '\0' || errno == ERANGE ||
        number > (unsigned long)limit) {
        fprintf(stderr, "Error: value '%s' for %s is outside 0..%d\n",
            arg, argname, limit);
        exit(1);
    }
    return (int)number;
}

static char *option_value(int argc, char **argv, int *index) {
    if (*index + 1 >= argc) {
        fprintf(stderr, "Error: option %s requires a value\n", argv[*index]);
        exit(1);
    }
    (*index)++;
    return argv[*index];
}

static void file_write_byte(int byte, FILE *ofp) {
    if (fputc(byte, ofp) != byte) {
        fprintf(stderr, "Error: Cannot write output file\n");
        perror("fputc");
        exit(1);
    }
}

static inline unsigned kc_bit_size(unsigned value) {
#   ifdef __GNUC__
    return ((sizeof(unsigned) * 8 - 1) ^ __builtin_clz(value));
#   else
    unsigned bits = 0;

    while (value >>= 1) {
        bits++;
    }

    return bits;
#   endif
}

static int read_byte(ctx* ctx) {
    if (ctx->packed_index >= ctx->packed_size) {
        fprintf(stderr, "Error: Compressed stream underrun, packed_index=%zu, packed_size=%zu\n", ctx->packed_index, ctx->packed_size);
        exit(1);
    }
    return ctx->packed_data[ctx->packed_index++];
}

static int read_bit(ctx* ctx) {
    if ((ctx->packed_bit_mask >>= 1) == 0) {
        ctx->packed_bit_mask = 0x80;
        ctx->packed_bit_value = read_byte(ctx);
    }
    return (ctx->packed_bit_value & ctx->packed_bit_mask) != 0;
}

static int read_interlaced_elias_gamma(ctx* ctx, int inverted, int skip) {
    int value = 1;
    while (skip || !read_bit(ctx)) {
        skip = 0;
        value = (value << 1) | (read_bit(ctx) ^ inverted);
    }
    return value;
}

static int read_interlaced_elias_gamma_le(ctx* ctx, int inverted, int skip) {
    unsigned int value = (unsigned int)read_interlaced_elias_gamma(ctx, inverted, skip);
    unsigned int bits = kc_bit_size(value);

    if (DALI_ELIAS_LE && bits >= 8) {
        unsigned int payload_mask = (1U << bits) - 1U;
        unsigned int payload = value & payload_mask;
        unsigned int high = payload >> (bits - 8);
        unsigned int rest = payload & ((1U << (bits - 8)) - 1U);
        payload = (rest << 8) | high;
        value = (1U << bits) | payload;
    }

    return (int)value;
}


static void copy_inplace_literal(ctx* ctx) {
    size_t remaining = ctx->unpacked_size - ctx->unpacked_index;

    if (ctx->packed_index + remaining > BUFFER_SIZE) {
        fprintf(stderr, "Error: Packed stream too large for inplace literal copy\n");
        exit(1);
    }

    memcpy(ctx->packed_data + ctx->packed_index, ctx->unpacked_data + ctx->unpacked_index, remaining);
    ctx->packed_index += remaining;
}

static void adjust_inplace_stream(ctx* ctx) {
    int length;
    int overwrite;
    int bit;
    int byte;
    size_t packed_size_for_overlap;

    int safe_input_index = 0;
    int safe_output_index = 0;

    ctx->packed_index = 0;
    ctx->packed_bit_mask = 0;
    ctx->packed_bit_value = 0;
    ctx->unpacked_index = 0;

    if (ctx->dali) {
        /* Remove the classic 18-bit Dali EOD. */
        if (ctx->packed_data[ctx->packed_size - 1] & 0x80)
            ctx->packed_size -= 3;
        else
            ctx->packed_size -= 2;
        packed_size_for_overlap = ctx->packed_size;
    }
    else {
        /* Raw-high EOD is two control bits followed by one raw zero byte.
         * Keep the original read limit while parsing because the control byte
         * can contain bits from the prior token. */
        if (ctx->packed_size == 0 || ctx->packed_data[ctx->packed_size - 1] != 0) {
            fprintf(stderr, "Error: Invalid raw-high end marker\n");
            exit(1);
        }
        packed_size_for_overlap = ctx->packed_size - 1;
    }

    while (1) {
        int lit_len = read_interlaced_elias_gamma_le(ctx, FALSE, 0);
        length = lit_len;
        while (length--) {
            read_byte(ctx);
        }

        ctx->unpacked_index += (size_t)lit_len;

        overwrite = (int)ctx->unpacked_index - (int)(ctx->unpacked_size - packed_size_for_overlap + ctx->packed_index);
        if (ctx->inplace && overwrite >= 0) {
            ctx->unpacked_index = (size_t)safe_input_index;
            ctx->packed_index = (size_t)safe_output_index;
            copy_inplace_literal(ctx);
            ctx->packed_size = ctx->packed_index;
            return;
        }

        safe_input_index = (int)ctx->unpacked_index;
        safe_output_index = (int)ctx->packed_index;

        bit = read_bit(ctx);
        if (!bit) {
            int rep_len = read_interlaced_elias_gamma_le(ctx, FALSE, 0);
            ctx->unpacked_index += (size_t)rep_len;

            overwrite = (int)ctx->unpacked_index - (int)(ctx->unpacked_size - packed_size_for_overlap + ctx->packed_index);
            if (ctx->inplace && overwrite >= 0) {
                ctx->unpacked_index = (size_t)safe_input_index;
                ctx->packed_index = (size_t)safe_output_index;
                copy_inplace_literal(ctx);
                ctx->packed_size = ctx->packed_index;
                return;
            }

            safe_input_index = (int)ctx->unpacked_index;
            safe_output_index = (int)ctx->packed_index;

            bit = read_bit(ctx);
        }

        while (bit) {
            if (ctx->dali) {
                int offset_high = read_interlaced_elias_gamma_le(ctx, FALSE, 0);
                if (offset_high == 256)
                    return;
            }
            else {
                int short_offset = read_bit(ctx);
                if (!short_offset && read_byte(ctx) == 0) {
                    ctx->packed_size = packed_size_for_overlap;
                    return;
                }
            }

            byte = read_byte(ctx);
            if (byte & 1) {
                length = 2;
            } else {
                length = read_interlaced_elias_gamma_le(ctx, FALSE, 1) + 1;
            }

            ctx->unpacked_index += (size_t)length;

            overwrite = (int)ctx->unpacked_index - (int)(ctx->unpacked_size - packed_size_for_overlap + ctx->packed_index);
            if (ctx->inplace && overwrite >= 0) {
                ctx->unpacked_index = (size_t)safe_input_index;
                ctx->packed_index = (size_t)safe_output_index;
                copy_inplace_literal(ctx);
                ctx->packed_size = ctx->packed_index;
                return;
            }

            safe_input_index = (int)ctx->unpacked_index;
            safe_output_index = (int)ctx->packed_index;

            bit = read_bit(ctx);
        }
    }
}

static unsigned int get_var(ctx* ctx, unsigned int pos) {
    return ctx->sfx_code[pos] + (ctx->sfx_code[pos + 1] << 8);
}

/* The original SFX moves the stream to the top of RAM. Check that no
 * output run destroys bytes that the decoder has not consumed yet. */
static void validate_sfx_stream(ctx *ctx) {
    int state = 0;
    size_t output = 0;
    size_t source = BUFFER_SIZE - ctx->packed_size;
    ctx->packed_index = 0;
    ctx->packed_bit_mask = 0;
    for (;;) {
        int length;
        int command = state == 0 ? 0 : read_bit(ctx);
        if (command) {
            int high = read_interlaced_elias_gamma_le(ctx, FALSE, 0);
            if (high == 256) break;
            int metadata = read_byte(ctx);
            length = (metadata & 1) ? 2 :
                read_interlaced_elias_gamma_le(ctx, FALSE, 1) + 1;
            state = 2;
        } else {
            length = read_interlaced_elias_gamma_le(ctx, FALSE, 0);
            if (state != 1) {
                for (int i = 0; i < length; i++) read_byte(ctx);
                state = 1;
            } else {
                state = 2;
            }
        }
        output += (size_t)length;
        if ((size_t)ctx->cbm_orig_addr + output > source + ctx->packed_index) {
            fprintf(stderr, "Error: SFX output would overwrite unread packed data at top of RAM\n");
            exit(1);
        }
    }
}

static void write_packed_stream(ctx* ctx) {
    FILE *fp = NULL;
    unsigned int dali_sfx_src;
    unsigned int dali_src;
    unsigned int dali_dst;
    unsigned int dali_sfx_addr;
    unsigned int dali_data_end;
    unsigned int dali_data_size_hi;
    unsigned int dali_01;
    unsigned int dali_cli;

    unsigned int var_dali_sfx_src;
    unsigned int var_dali_src;
    unsigned int var_dali_dst;
    unsigned int var_dali_sfx_addr;
    unsigned int var_dali_data_end;
    unsigned int var_dali_data_size_hi;
    unsigned int var_dali_01;
    unsigned int var_dali_cli;

    unsigned int sfx_addr = 0x801;
    int before_reloc = -1;

    if (ctx->sfx) {
        printf("Creating sfx with start-address $%04x\n", ctx->sfx_addr);
        if (ctx->sfx_small) {
            if (ctx->sfx_effect) {
                ctx->sfx_size = sizeof(decruncher_small_effect);
                ctx->sfx_code = (unsigned char *)malloc(ctx->sfx_size);
                memcpy(ctx->sfx_code, decruncher_small_effect, ctx->sfx_size);
            } else {
                ctx->sfx_size = sizeof(decruncher_small);
                ctx->sfx_code = (unsigned char *)malloc(ctx->sfx_size);
                memcpy(ctx->sfx_code, decruncher_small, ctx->sfx_size);
            }
        } else {
            if (ctx->sfx_effect) {
                ctx->sfx_size = sizeof(decruncher_effect);
                ctx->sfx_code = (unsigned char *)malloc(ctx->sfx_size);
                memcpy(ctx->sfx_code, decruncher_effect, ctx->sfx_size);
            } else {
                ctx->sfx_size = sizeof(decruncher);
                ctx->sfx_code = (unsigned char *)malloc(ctx->sfx_size);
                memcpy(ctx->sfx_code, decruncher, ctx->sfx_size);
            }
        }
        ctx->sfx_size -= DALI_VARS_SIZE;

        unsigned int load = ctx->cbm_relocate_sfx_addr >= 0 ?
            (unsigned int)ctx->cbm_relocate_sfx_addr : 0x0801;
        unsigned int image_size = (unsigned int)ctx->sfx_size - 2 +
            (unsigned int)ctx->packed_size - (ctx->cbm_relocate_sfx_addr >= 0 ? 12 : 0);
        if (load < 0x0200 || load + image_size > BUFFER_SIZE ||
            (load < 0xc000 && load + 32 > 0xa000) || load + 32 > 0xd000) {
            fprintf(stderr, "Error: SFX load image does not fit RAM or bootstrap overlaps banked ROM/I/O\n");
            exit(1);
        }
        validate_sfx_stream(ctx);

        var_dali_sfx_src = ctx->sfx_size + 0;
        var_dali_src = ctx->sfx_size + 2;
        var_dali_dst = ctx->sfx_size + 4;
        var_dali_sfx_addr = ctx->sfx_size + 6;
        var_dali_data_end = ctx->sfx_size + 8;
        var_dali_data_size_hi = ctx->sfx_size + 10;
        var_dali_01 = ctx->sfx_size + 12;
        var_dali_cli = ctx->sfx_size + 14;

        dali_sfx_src = get_var(ctx, var_dali_sfx_src);
        dali_src = get_var(ctx, var_dali_src);
        dali_dst = get_var(ctx, var_dali_dst);
        dali_sfx_addr = get_var(ctx, var_dali_sfx_addr);
        dali_data_end = get_var(ctx, var_dali_data_end);
        dali_data_size_hi = get_var(ctx, var_dali_data_size_hi);
        dali_01 = get_var(ctx, var_dali_01);
        dali_cli = get_var(ctx, var_dali_cli);

        ctx->sfx_code[dali_sfx_addr + 0] = ctx->sfx_addr & 0xff;
        ctx->sfx_code[dali_sfx_addr + 1] = (ctx->sfx_addr >> 8) & 0xff;

        ctx->sfx_code[dali_dst + 0] = ctx->cbm_orig_addr & 0xff;
        ctx->sfx_code[dali_dst + 1] = (ctx->cbm_orig_addr >> 8) & 0xff;

        ctx->sfx_code[dali_src + 0] = (0x10000 - (unsigned int)ctx->packed_size) & 0xff;
        ctx->sfx_code[dali_src + 1] = ((0x10000 - (unsigned int)ctx->packed_size) >> 8) & 0xff;

        ctx->sfx_code[dali_data_end + 0] = (sfx_addr + ctx->sfx_size - 2 + (unsigned int)ctx->packed_size - 0x100) & 0xff;
        ctx->sfx_code[dali_data_end + 1] = ((sfx_addr + ctx->sfx_size - 2 + (unsigned int)ctx->packed_size - 0x100) >> 8) & 0xff;

        ctx->sfx_code[dali_data_size_hi] = 0xff - (((ctx->packed_size + 0x100) >> 8) & 0xff);

        if (!ctx->sfx_small) {
            if (ctx->sfx_01 < 0) ctx->sfx_01 = 0x37;
            ctx->sfx_code[dali_01] = ctx->sfx_01;
            if (ctx->sfx_cli) ctx->sfx_code[dali_cli] = 0x58;
        }

        fp = fopen(ctx->output_name, "wb");
        if (!fp) {
            fprintf(stderr, "Error: Cannot create output file (%s)\n", ctx->output_name);
            exit(1);
        }
        printf("original: $%04x-$%04x ($%04x) 100%%\n", (int)ctx->cbm_orig_addr, (int)ctx->cbm_orig_addr + (int)ctx->unpacked_size, (int)ctx->unpacked_size);
        if (ctx->cbm_relocate_sfx_addr >= 0) {
            sfx_addr = ctx->cbm_relocate_sfx_addr;
            if (ctx->sfx_small) {
                ctx->sfx_code[dali_sfx_src + 0] = (ctx->cbm_relocate_sfx_addr + 0xd) & 255;
                ctx->sfx_code[dali_sfx_src + 1] = (ctx->cbm_relocate_sfx_addr + 0xd) >> 8;
            } else {
                ctx->sfx_code[dali_sfx_src + 0] = (ctx->cbm_relocate_sfx_addr + 0x13) & 255;
                ctx->sfx_code[dali_sfx_src + 1] = (ctx->cbm_relocate_sfx_addr + 0x13) >> 8;
            }
            ctx->sfx_code[dali_data_end + 0] = (ctx->cbm_relocate_sfx_addr + ctx->sfx_size - 2 + (unsigned int)ctx->packed_size - 0x100 - 0x0c) & 0xff;
            ctx->sfx_code[dali_data_end + 1] = ((ctx->cbm_relocate_sfx_addr + ctx->sfx_size - 2 + (unsigned int)ctx->packed_size - 0x100 - 0x0c) >> 8) & 0xff;

            fputc(ctx->cbm_relocate_sfx_addr & 255, fp);
            fputc(ctx->cbm_relocate_sfx_addr >> 8, fp);

            if (fwrite(ctx->sfx_code + 0xe, sizeof(char), ctx->sfx_size - 0xe, fp) != (size_t)(ctx->sfx_size - 0xe)) {
                fprintf(stderr, "Error: Cannot write output file %s\n", ctx->output_name);
                exit(1);
            }
        } else {
            if (fwrite(ctx->sfx_code, sizeof(char), ctx->sfx_size, fp) != (size_t)ctx->sfx_size) {
                fprintf(stderr, "Error: Cannot write output file %s\n", ctx->output_name);
                exit(1);
            }
        }
        printf("packed:   $%04x-$%04x ($%04x) %3.2f%%\n", sfx_addr, sfx_addr + (int)ctx->sfx_size + (int)ctx->packed_size, (int)ctx->sfx_size + (int)ctx->packed_size, ((float)(ctx->packed_size + (int)ctx->sfx_size) / (float)(ctx->unpacked_size) * 100.0));
    } else {
        fp = fopen(ctx->output_name, "wb");
        if (!fp) {
            fprintf(stderr, "Error: Cannot create output file (%s)\n", ctx->output_name);
            exit(1);
        }
        if (ctx->cbm_relocate_origin_addr >= 0) {
            ctx->cbm_orig_addr = ctx->cbm_relocate_origin_addr;
            ctx->cbm = TRUE;
        }

        if (ctx->cbm_relocate_packed_addr >= 0) {
            if (ctx->inplace) {
                before_reloc = ctx->cbm_range_to - (int)ctx->packed_size - 2;
            } else {
                before_reloc = ctx->cbm_range_to - 2 - (int)(ctx->unpacked_size - (ctx->unpacked_size - ctx->packed_size));
            }
            ctx->cbm_packed_addr = ctx->cbm_relocate_packed_addr;
        } else {
            if (ctx->inplace) {
                ctx->cbm_packed_addr = ctx->cbm_range_to - (int)ctx->packed_size - 2;
            } else {
                ctx->cbm_packed_addr = ctx->cbm_range_to - 2 - (int)(ctx->unpacked_size - (ctx->unpacked_size - ctx->packed_size));
            }
        }

        if (ctx->cbm) {
            printf("original: $%04x-$%04x ($%04x) 100%%\n", (int)ctx->cbm_orig_addr, (int)ctx->cbm_orig_addr + (int)ctx->unpacked_size, (int)ctx->unpacked_size);
            if (before_reloc >= 0 && ctx->inplace) {
                printf("packed:   $%04x-$%04x ($%04x) %3.2f%%\n", (int)before_reloc, (int)before_reloc + (int)ctx->packed_size + 2, (int)ctx->packed_size + 2, ((float)(ctx->packed_size) / (float)(ctx->unpacked_size) * 100.0));
                printf("reloc:    $%04x-$%04x ($%04x) %3.2f%%\n", (int)ctx->cbm_packed_addr, (int)ctx->cbm_packed_addr + (int)ctx->packed_size + 2, (int)ctx->packed_size + 2, ((float)(ctx->packed_size) / (float)(ctx->unpacked_size) * 100.0));
            } else {
                printf("packed:   $%04x-$%04x ($%04x) %3.2f%%\n", (int)ctx->cbm_packed_addr + 2, (int)ctx->cbm_packed_addr + (int)ctx->packed_size + 2, (int)ctx->packed_size, ((float)(ctx->packed_size) / (float)(ctx->unpacked_size) * 100.0));
            }
            if ((ctx->cbm_packed_addr >= 0xd000 && ctx->cbm_packed_addr < 0xe000) || (ctx->cbm_packed_addr < 0xd000 && ctx->cbm_packed_addr + (int)ctx->packed_size + 2 > 0xd000)) {
                fprintf(stderr, "Warning: Packed file lies in I/O-range from $d000-$dfff\n");
                if (ctx->exit_on_warn) exit(1);
            }

            file_write_byte(ctx->cbm_packed_addr & 255, fp);
            file_write_byte((ctx->cbm_packed_addr >> 8) & 255, fp);

            file_write_byte(ctx->cbm_orig_addr & 255, fp);
            file_write_byte((ctx->cbm_orig_addr >> 8) & 255, fp);
        } else {
            printf("original: $%04x-$%04x ($%04x) 100%%\n", 0, (int)ctx->unpacked_size, (int)ctx->unpacked_size);
            printf("packed:   $%04x-$%04x ($%04x) %3.2f%%\n", 0, (int)ctx->packed_size, (int)ctx->packed_size, ((float)(ctx->packed_size) / (float)(ctx->unpacked_size) * 100.0));
        }
    }

    if (fwrite(ctx->packed_data, sizeof(char), ctx->packed_size, fp) != ctx->packed_size) {
        fprintf(stderr, "Error: Cannot write output file\n");
        exit(1);
    }
    fclose(fp);
}

static unsigned char *read_file(const char *name, size_t *size_out) {
    FILE *fp = fopen(name, "rb");
    unsigned char *data;
    size_t size;

    if (!fp) {
        fprintf(stderr, "Error: Cannot access file %s\n", name);
        exit(1);
    }

    fseek(fp, 0, SEEK_END);
    size = (size_t)ftell(fp);
    fseek(fp, 0, SEEK_SET);

    data = (unsigned char *)malloc(size);
    if (!data) {
        fclose(fp);
        fprintf(stderr, "Error: Insufficient memory\n");
        exit(1);
    }

    if (size != 0 && fread(data, 1, size, fp) != size) {
        fclose(fp);
        free(data);
        fprintf(stderr, "Error: Cannot read file %s\n", name);
        exit(1);
    }
    fclose(fp);

    *size_out = size;
    return data;
}

static void do_decompress(ctx* ctx) {
    FILE *fp;
    long fsz;
    unsigned char *packed;
    unsigned char *out;
    int hdr;
    int out_sz;
    const char *decode_error;
    char *generated_name = NULL;

    fp = fopen(ctx->input_name, "rb");
    if (!fp) {
        fprintf(stderr, "Error: Cannot open '%s'\n", ctx->input_name);
        exit(1);
    }
    if (fseek(fp, 0, SEEK_END) != 0 || (fsz = ftell(fp)) < 0 ||
        fseek(fp, 0, SEEK_SET) != 0 || fsz > PACKED_BUFFER_SIZE + 4) {
        fclose(fp);
        fprintf(stderr, "Error: Cannot size compressed input\n");
        exit(1);
    }
    packed = (unsigned char *)malloc((size_t)fsz);
    out = (unsigned char *)malloc(BUFFER_SIZE);
    if (!packed || !out || (fsz != 0 && fread(packed, 1, (size_t)fsz, fp) != (size_t)fsz)) {
        fclose(fp);
        free(packed);
        free(out);
        fprintf(stderr, "Error: Cannot read compressed input\n");
        exit(1);
    }
    fclose(fp);

    hdr = (ctx->cbm && fsz >= 4) ? 4 : 0;
    if (!decode_stream(ctx, packed + hdr, (int)(fsz - hdr), out, BUFFER_SIZE,
          &out_sz, &decode_error)) {
        fprintf(stderr, "Error: Cannot decode %s stream: %s\n",
            stream_name(ctx), decode_error);
        free(packed);
        free(out);
        exit(1);
    }

    if (!ctx->output_name) {
        generated_name = (char *)malloc(strlen(ctx->input_name) + 5);
        if (!generated_name) {
            free(packed);
            free(out);
            fprintf(stderr, "Error: Insufficient memory\n");
            exit(1);
        }
        strcpy(generated_name, ctx->input_name);
        {
            char *dot = strrchr(generated_name, '.');
            if (dot && !strcmp(dot, ".lz"))
                *dot = 0;
        }
        strcat(generated_name, ".out");
        ctx->output_name = generated_name;
    }
    fp = fopen(ctx->output_name, "wb");
    if (!fp || (hdr && (fputc(packed[2], fp) == EOF || fputc(packed[3], fp) == EOF)) ||
        fwrite(out, 1, (size_t)out_sz, fp) != (size_t)out_sz) {
        if (fp)
            fclose(fp);
        free(packed);
        free(out);
        free(generated_name);
        fprintf(stderr, "Error: Cannot write decompressed output\n");
        exit(1);
    }
    if (fclose(fp) != 0) {
        free(packed);
        free(out);
        free(generated_name);
        fprintf(stderr, "Error: Cannot close decompressed output\n");
        exit(1);
    }
    printf("Decompressed %s stream: %ld bytes -> %s (%d bytes)\n",
        stream_name(ctx), fsz, ctx->output_name, out_sz + hdr / 2);
    free(packed);
    free(out);
    free(generated_name);
}

static void do_verify(ctx* ctx) {
    size_t osz;
    unsigned char *orig = read_file(ctx->input_name, &osz);
    int hdr = (ctx->cbm && osz >= 2) ? 2 : 0;
    if (osz <= (size_t)hdr || osz - hdr > MAX_VARLEN) {
        fprintf(stderr, "Error: Payload must contain 1..65535 bytes\n");
        free(orig);
        exit(1);
    }
    unsigned char *data = orig + hdr;
    int dsz = (int)(osz - hdr);
    if (hdr) ctx->cbm_orig_addr = orig[0] | (orig[1] << 8);
    printf("Verify: %s (%d bytes)\n", ctx->input_name, (int)osz);

    unsigned char *pkd;
    size_t pksz;
    int packed_header = 0;
    if (ctx->verify_packed_name) {
        pkd = read_file(ctx->verify_packed_name, &pksz);
        packed_header = (ctx->cbm && pksz >= 4) ? 4 : 0;
        printf("  Packed fixture: %s (%zu bytes)\n",
            ctx->verify_packed_name, pksz);
    }
    else {
        pkd = (unsigned char*)malloc(PACKED_BUFFER_SIZE);
        if (!pkd) {
            fprintf(stderr, "Error: Insufficient memory\n");
            exit(1);
        }
        pksz = salvador_compress(data, pkd, (size_t)dsz, PACKED_BUFFER_SIZE,
            &ctx->search_config,
            FLG_IS_INVERTED | (ctx->dali ? FLG_IS_DALI : 0),
            0, 0, NULL, NULL, NULL);
        if (pksz == (size_t)-1) {
            fprintf(stderr, "Error: Compression failed during verification\n");
            exit(1);
        }
        printf("  Compressed: %d -> %d bytes (%.2f%%)\n",
            dsz, (int)pksz, dsz ? 100.0*pksz/dsz : 0.0);
    }

    unsigned char *dec = (unsigned char*)malloc(BUFFER_SIZE);
    int decsz;
    const char *decode_error;
    if (!decode_stream(ctx, pkd + packed_header, (int)pksz - packed_header,
          dec, BUFFER_SIZE, &decsz, &decode_error)) {
        fprintf(stderr, "Error: Verification decoder failed: %s\n", decode_error);
        exit(1);
    }
    printf("  Decompressed: %d bytes\n", decsz);

    int errs = 0, cmp = decsz < dsz ? decsz : dsz;
    for (int i = 0; i < cmp; i++)
        if (dec[i] != data[i]) { if (!errs) printf("  First mismatch at byte %d\n", i + hdr); errs++; }
    if (!errs && decsz == dsz) printf("  OK - Round-trip verified!\n");
    else { printf("  FAILED - %d mismatches", errs);
        if (decsz != dsz) printf(" + size diff (%d vs %d)", decsz, dsz);
        printf("\n"); exit(1); }
    free(orig); free(pkd); free(dec);
}

static void do_compress(ctx* ctx) {
    unsigned char *dict_data = NULL;
    size_t dict_size = 0;
    unsigned char *src_data = NULL;
    size_t src_size = 0;
    size_t max_packed_size = 0;
    FILE* ufp = NULL;
    salvador_stats stats;
    salvador_stats *stats_ptr = NULL;

    if (ctx->output_name == NULL) {
        ctx->output_name = (char *)malloc(strlen(ctx->input_name) + 4);
        strcpy(ctx->output_name, ctx->input_name);
        strcat(ctx->output_name, ".lz");
        printf("output name: %s\n", ctx->output_name);
    }

    ctx->packed_data = (unsigned char *)malloc(PACKED_BUFFER_SIZE);
    ctx->unpacked_data = (unsigned char *)malloc(BUFFER_SIZE + 2);

    if (!ctx->packed_data || !ctx->unpacked_data) {
        fprintf(stderr, "Error: Insufficient memory\n");
        exit(1);
    }

    ufp = fopen(ctx->input_name, "rb");
    if (!ufp) {
        fprintf(stderr, "Error: Cannot access input file\n");
        exit(1);
    }
    ctx->unpacked_size = fread(ctx->unpacked_data, sizeof(char), BUFFER_SIZE + 2, ufp);
    if (ferror(ufp) || fgetc(ufp) != EOF ||
        ctx->unpacked_size > (size_t)(BUFFER_SIZE + (ctx->cbm ? 2 : 0))) {
        fprintf(stderr, "Error: Cannot read input or input exceeds 64 KiB\n");
        fclose(ufp);
        exit(1);
    }
    fclose(ufp);

    if (ctx->cbm_relocate_origin_addr >= 0) {
        ctx->cbm_orig_addr = ctx->cbm_relocate_origin_addr;
    } else if (!ctx->cbm) {
        ctx->cbm_orig_addr = 0;
    } else if (ctx->unpacked_size >= 2) {
        ctx->cbm_orig_addr = ctx->unpacked_data[0] + (ctx->unpacked_data[1] << 8);
    } else {
        fprintf(stderr, "Error: CBM input is missing its two-byte load address\n");
        exit(1);
    }

    if (ctx->cbm) {
        ctx->unpacked_data += 2;
        ctx->unpacked_size -= 2;
    }

    if (ctx->cbm_range_from < 0) ctx->cbm_range_from = ctx->cbm_orig_addr;
    if (ctx->cbm_range_to < 0) ctx->cbm_range_to = ctx->cbm_orig_addr + (int)ctx->unpacked_size;

    if ((ctx->cbm_range_to - ctx->cbm_orig_addr) > (int)ctx->unpacked_size) {
        ctx->cbm_range_to = (int)ctx->unpacked_size + ctx->cbm_orig_addr;
        fprintf(stderr, "Warning: File ends at $%04x, adopting --to value\n", ctx->cbm_range_to);
    }
    ctx->unpacked_size = (size_t)(ctx->cbm_range_to - ctx->cbm_orig_addr);

    if (ctx->cbm_range_from < ctx->cbm_orig_addr) {
        ctx->cbm_range_from = ctx->cbm_orig_addr;
        fprintf(stderr, "Warning: File starts at $%04x, adopting --from value\n", ctx->cbm_range_from);
    }
    if (ctx->cbm_range_from > ctx->cbm_range_to) {
        fprintf(stderr, "Error: --from beyond fileend ($%04x - $%04x)\n", ctx->cbm_range_from, ctx->cbm_range_to);
        exit(1);
    }

    if (ctx->cbm_prefix_from >= 0) {
        if (ctx->cbm_range_from < 0) {
            fprintf(stderr, "Error: Dict is zero size (use --from)\n");
            exit(1);
        }
        else if (ctx->cbm_prefix_from >= ctx->cbm_range_from) {
            fprintf(stderr, "Error: --from must be greater than --prefix-from\n");
            exit(1);
        }
        if (ctx->cbm_range_from >= 0 && ctx->cbm_range_from - ctx->cbm_prefix_from > 32640) {
            fprintf(stderr, "Info: --prefix-from exceeds max offset, not all bytes can be used\n");
        }
        if (ctx->cbm_prefix_from < ctx->cbm_orig_addr) {
            ctx->cbm_prefix_from = ctx->cbm_orig_addr;
            fprintf(stderr, "Warning: File starts at $%04x, adopting --prefix-from value\n", ctx->cbm_prefix_from);
        }
        dict_data = ctx->unpacked_data + ctx->cbm_prefix_from - ctx->cbm_orig_addr;
        dict_size = (size_t)(ctx->cbm_range_from - ctx->cbm_prefix_from);
    }

    if (ctx->prefix_name) {
        size_t file_dict_size = 0;
        unsigned char *file_dict_data = read_file(ctx->prefix_name, &file_dict_size);
        if (file_dict_size > 32640) {
            fprintf(stderr, "Info: prefix file exceeds max offset, truncating to 32640 bytes\n");
            file_dict_size = 32640;
        }
        dict_data = file_dict_data;
        dict_size = file_dict_size;
    }

    ctx->unpacked_data += (ctx->cbm_range_from - ctx->cbm_orig_addr);
    ctx->unpacked_size -= (size_t)(ctx->cbm_range_from - ctx->cbm_orig_addr);
    ctx->cbm_orig_addr = ctx->cbm_range_from;

    if (ctx->unpacked_size > MAX_VARLEN) {
        fprintf(stderr, "Error: Payload must contain 1..65535 bytes\n");
        exit(1);
    }

    if (ctx->sfx && (ctx->cbm_orig_addr < 0x0200 ||
        (size_t)ctx->cbm_orig_addr + ctx->unpacked_size > BUFFER_SIZE)) {
        fprintf(stderr, "Error: SFX output must fit $0200..$ffff (zero page and stack are in use)\n");
        exit(1);
    }

    if (ctx->unpacked_size == 0) {
        fprintf(stderr, "Error: Input too small\n");
        exit(1);
    }

    printf("Compressing from $%04x to $%04x = $%04x bytes\n", (int)ctx->cbm_range_from, (int)ctx->cbm_range_to, (int)ctx->unpacked_size);

    src_size = dict_size + ctx->unpacked_size;
    src_data = (unsigned char *)malloc(src_size);
    if (!src_data) {
        fprintf(stderr, "Error: Insufficient memory\n");
        exit(1);
    }

    if (dict_size) {
        memcpy(src_data, dict_data, dict_size);
    }
    memcpy(src_data + dict_size, ctx->unpacked_data, ctx->unpacked_size);

    max_packed_size = salvador_get_max_compressed_size(src_size);
    if (max_packed_size > PACKED_BUFFER_SIZE) {
        fprintf(stderr, "Error: Packed stream too large for buffer\n");
        exit(1);
    }

    if (ctx->show_stats) {
        memset(&stats, 0, sizeof(stats));
        stats_ptr = &stats;
    }
    ctx->packed_size = salvador_compress(src_data, ctx->packed_data,
        src_size, PACKED_BUFFER_SIZE, &ctx->search_config,
        FLG_IS_INVERTED | (ctx->dali ? FLG_IS_DALI : 0),
        0, dict_size, NULL, stats_ptr, NULL);
    if (ctx->packed_size == (size_t)-1) {
        fprintf(stderr, "Error: Compression error\n");
        exit(1);
    }

    if (ctx->show_stats && stats_ptr) {
        int avg_literals = (stats.literals_divisor > 0) ? (stats.total_literals / stats.literals_divisor) : 0;
        int avg_match_len = (stats.match_divisor > 0) ? (stats.total_match_lens / stats.match_divisor) : 0;
        int avg_offset = (stats.match_divisor > 0) ? (int)(stats.total_offsets / stats.match_divisor) : 0;
        int avg_rle1 = (stats.rle1_divisor > 0) ? (stats.total_rle1_lens / stats.rle1_divisor) : 0;
        int pct_rle1 = (stats.match_divisor > 0) ? (stats.rle1_divisor * 100 / stats.match_divisor) : 0;

        printf("stats: literals count=%d avg=%d min=%d max=%d\n",
               stats.literals_divisor, avg_literals, stats.min_literals, stats.max_literals);
        printf("stats: literals bytes=%d nonzero_runs=%d\n",
               stats.total_literals, stats.nonzero_literal_runs);
        printf("stats: matches normal=%d rep=%d avg_len=%d min_len=%d max_len=%d\n",
               stats.num_normal_matches, stats.num_rep_matches, avg_match_len,
               stats.min_match_len, stats.max_match_len);
        printf("stats: offsets avg=%d min=%d max=%d\n",
               avg_offset, stats.min_offset, stats.max_offset);
        printf("stats: 1-offsets matches=%d pct=%d avg_len=%d min_len=%d max_len=%d\n",
               stats.rle1_divisor, pct_rle1, avg_rle1, stats.min_rle1_len, stats.max_rle1_len);
    }

    if (ctx->inplace) {
        adjust_inplace_stream(ctx);
    }

    if (ctx->inplace && ctx->packed_size + 2 > ctx->unpacked_size) {
        fprintf(stderr, "Error: Packed file is too large for in-place decompression\n");
        exit(1);
    }

    write_packed_stream(ctx);

    free(src_data);
    if (ctx->prefix_name && dict_data) {
        free(dict_data);
    }
}

int main(int argc, char *argv[]) {
    int i;
    int show_version = FALSE;
    int exit_help = 1;

    ctx ctx = { 0 };

    ctx.output_name = NULL;
    ctx.input_name = NULL;
    ctx.prefix_name = NULL;
    ctx.verify_packed_name = NULL;

    ctx.inplace = -1;

    ctx.cbm = TRUE;
    ctx.cbm_orig_addr = 0;
    ctx.cbm_packed_addr = 0;
    ctx.cbm_range_from = -1;
    ctx.cbm_range_to = -1;
    ctx.cbm_relocate_packed_addr = -1;
    ctx.cbm_relocate_origin_addr = -1;
    ctx.cbm_relocate_sfx_addr = -1;
    ctx.cbm_prefix_from = -1;

    ctx.sfx = FALSE;
    ctx.sfx_addr = -1;
    ctx.sfx_01 = -1;
    ctx.sfx_cli = FALSE;
    ctx.sfx_small = FALSE;
    ctx.sfx_effect = FALSE;
    ctx.sfx_code = NULL;
    ctx.exit_on_warn = FALSE;
    ctx.show_stats = FALSE;
    ctx.max_match_len = 0;
    ctx.speed_bias = 2;
    ctx.dali = FALSE;
    ctx.short_runs = FALSE;
    ctx.search_config = *kc_search_baseline();
    int do_verify_mode = 0;
    int do_decompress_mode = 0;

    for (i = 1; i < argc; i++) {
        if (!strncmp(argv[i], "-", 1) || !strncmp(argv[i], "--", 2)) {
            if (!strcmp(argv[i], "--binfile")) {
                ctx.cbm = FALSE;
            } else if (!strcmp(argv[i], "--prefix-from")) {
                char *option = argv[i];
                ctx.cbm_prefix_from = read_number(option_value(argc, argv, &i), option, 65536);
            } else if (!strcmp(argv[i], "--prefix-file")) {
                ctx.prefix_name = option_value(argc, argv, &i);
            } else if (!strcmp(argv[i], "--version") || !strcmp(argv[i], "-V")) {
                show_version = TRUE;
            } else if (!strcmp(argv[i], "--help") || !strcmp(argv[i], "-h")) {
                exit_help = 0;
            } else if (!strcmp(argv[i], "--exit_on_warn")) {
                ctx.exit_on_warn = TRUE;
            } else if (!strcmp(argv[i], "--stats")) {
                ctx.show_stats = TRUE;
            } else if (!strcmp(argv[i], "--verify")) {
                do_verify_mode = 1;
            } else if (!strcmp(argv[i], "--verify-packed")) {
                ctx.verify_packed_name = option_value(argc, argv, &i);
                do_verify_mode = 1;
            } else if (!strcmp(argv[i], "--decode") ||
                       !strcmp(argv[i], "--decompress") || !strcmp(argv[i], "-d")) {
                do_decompress_mode = 1;
            } else if (!strcmp(argv[i], "--max-match")) {
                char *option = argv[i];
                ctx.max_match_len = read_number(option_value(argc, argv, &i), option, 65535);
            } else if (!strcmp(argv[i], "--speed")) {
                char *option = argv[i];
                ctx.speed_bias = read_number(option_value(argc, argv, &i), option, 15);
            } else if (!strcmp(argv[i], "-dali") || !strcmp(argv[i], "--dali")) {
                ctx.dali = TRUE;
            } else if (!strcmp(argv[i], "--short")) {
                ctx.short_runs = TRUE;
            } else if (!strcmp(argv[i], "--no-inplace")) {
                ctx.inplace = FALSE;
            } else if (!strcmp(argv[i], "--small")) {
                ctx.sfx_small = TRUE;
            } else if (!strcmp(argv[i], "--effect")) {
                ctx.sfx_effect = TRUE;
            } else if (!strcmp(argv[i], "--inplace")) {
                ctx.inplace = TRUE;
            } else if (!strcmp(argv[i], "--relocate-packed")) {
                char *option = argv[i];
                ctx.cbm_relocate_packed_addr = read_number(option_value(argc, argv, &i), option, 65536);
            } else if (!strcmp(argv[i], "--relocate-origin")) {
                char *option = argv[i];
                ctx.cbm_relocate_origin_addr = read_number(option_value(argc, argv, &i), option, 65536);
            } else if (!strcmp(argv[i], "--relocate-sfx")) {
                char *option = argv[i];
                ctx.cbm_relocate_sfx_addr = read_number(option_value(argc, argv, &i), option, 65536);
            } else if (!strcmp(argv[i], "--from")) {
                char *option = argv[i];
                ctx.cbm_range_from = read_number(option_value(argc, argv, &i), option, 65536);
            } else if (!strcmp(argv[i], "--to")) {
                char *option = argv[i];
                ctx.cbm_range_to = read_number(option_value(argc, argv, &i), option, 65536);
            } else if (!strcmp(argv[i], "--01")) {
                char *option = argv[i];
                ctx.sfx_01 = read_number(option_value(argc, argv, &i), option, 255);
            } else if (!strcmp(argv[i], "--cli")) {
                ctx.sfx_cli = TRUE;
            } else if (!strcmp(argv[i], "--sfx")) {
                char *option = argv[i];
                ctx.sfx_addr = read_number(option_value(argc, argv, &i), option, 65535);
                ctx.sfx = TRUE;
            } else if (!strcmp(argv[i], "-o")) {
                ctx.output_name = option_value(argc, argv, &i);
            } else {
                fprintf(stderr, "Error: Unknown option %s\n", argv[i]);
                exit(1);
            }
        } else if (i == argc - 1) {
            ctx.input_name = argv[i];
        } else {
            fprintf(stderr, "Error: Unknown option %s\n", argv[i]);
            exit(1);
        }
    }

    if (ctx.sfx) ctx.dali = TRUE;
    printf("kabutocrunch - %s stream encoder/decoder\n", stream_name(&ctx));
    printf("based on salvador by Emmanuel Marty and Dali by Tobias Bindhammer\n");

    if (argc <= 2 && show_version) exit(0);
    if (argc == 1 || !exit_help) {
        fprintf(stderr, "Usage: %s [options] input\n"
                        "  --decode, --decompress, -d  Decode instead of encode (uses selected format).\n"
                        "  --verify                    Compress and byte-verify a round trip.\n"
                        "  --verify-packed [file]      Decode file and byte-compare it with input.\n"
                        "  -o [filename]               Set output filename.\n"
                        "  --sfx [num]                 Create a C64 SFX executable (automatically uses Dali).\n"
                        "  --01 [num]                  Set 01 to [num] after sfx.\n"
                        "  --cli                       Do a CLI after sfx, default is SEI.\n"
                        "  --small                     Use a very small depacker that fits into zeropage, but --01 and --cli are ignored and it trashes zeropage (!)\n"
                        "  --effect                    A very simple decrunch effect is applied\n"
                        "  --inplace                   Explicitly enable inplace-decompression (overwrites default).\n"
                        "  --no-inplace                Explicitly disable inplace-decompression (overwrites default).\n"
                        "  --binfile                   Input file is a raw binary without load-address.\n"
                        "  --from [num]                Compress file from [num] on.\n"
                        "  --to [num]                  Compress file until position [num].\n"
                        "  --prefix-from [num]         Use preceding data from [num] on as dictionary (in combination with --from).\n"
                        "  --prefix-file [file]        Use preceding data from [file] as dictionary.\n"
                        "  --relocate-packed [num]     Relocate packed data to desired address [num] (resulting file can't be decompressed inplace!)\n"
                        "  --relocate-origin [num]     Set load-address of source file to [num] prior to compression. If used on bin-files, load-address and depack-target is prepended on output.\n"
                        "  --relocate-sfx [num]        Set load-address sfx-packed file to [num], basic header will then be omitted.\n"
                        "  --exit_on_warn              Exit on warnings like they happen when crossing the i/o-range.\n"
                        "  --stats                     Show compression statistics.\n"
                        "  --max-match [num]           Limit match length to [num] (0 = unlimited).\n"
                        "  --speed [0..15]             Favor fewer, faster tokens (default 2; 0 = smallest).\n"
                        "  -dali, --dali               Use the classic Dali-compatible bitstream.\n"
                        "  --short                     Limit emitted match runs to 256 bytes.\n"
                        "  -h, --help                  Show this help page.\n"
                        "  -V, --version               Show version info and exit.\n"
                        ,argv[0]);
        if (argc <= (2 + show_version)) exit(exit_help);
    }

    if (!ctx.sfx && ctx.cbm_relocate_sfx_addr >= 0) {
        fprintf(stderr, "Info: No sfx, ignoring --relocate-sfx option\n");
    }
    if (!ctx.sfx && ctx.sfx_small) {
        fprintf(stderr, "Info: No sfx, ignoring --small option\n");
    }
    if (!ctx.sfx && ctx.sfx_01 >= 0) {
        fprintf(stderr, "Info: No sfx, ignoring --01 option\n");
    }
    if (!ctx.sfx && ctx.sfx_cli) {
        fprintf(stderr, "Info: No sfx, ignoring --cli option\n");
    }

    if (ctx.input_name == NULL) {
        fprintf(stderr, "Error: No input-filename given\n");
        exit(1);
    }

    if (ctx.sfx) {
        if (do_verify_mode || do_decompress_mode || ctx.prefix_name || ctx.cbm_prefix_from >= 0) {
            fprintf(stderr, "Error: SFX requires compression without a prefix dictionary or verify/decode mode; use make test for SFX execution verification\n");
            exit(1);
        }
        ctx.inplace = FALSE;
    }
    if (ctx.inplace < 0) {
        ctx.inplace = FALSE;
    }

    if (ctx.max_match_len < 0) {
        ctx.max_match_len = 0;
    }
    if (ctx.short_runs && (ctx.max_match_len == 0 || ctx.max_match_len > 256)) {
        ctx.max_match_len = 256;
    }
    kc_set_max_match_len(ctx.max_match_len);
    kc_set_token_cost(ctx.speed_bias + 1);

    if (do_verify_mode) {
        do_verify(&ctx);
    } else if (do_decompress_mode) {
        do_decompress(&ctx);
    } else {
        do_compress(&ctx);
    }
    return 0;
}
