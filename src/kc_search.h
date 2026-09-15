#ifndef KC_SEARCH_H
#define KC_SEARCH_H

#include <stddef.h>

typedef struct kc_search_config {
   const char *name;
   unsigned int max_arrivals_per_position;
   unsigned int forward_arrivals;
   unsigned int max_matches_per_position;
   unsigned int forward_repeat_depth;
   unsigned int exhaustive_length_threshold;
   unsigned int enable_long_match_early_break;
   unsigned int long_match_break_threshold;
   unsigned int remaining_match_threshold;
   unsigned int initial_supplement_matches;
   unsigned int supplement_trigger_length;
   unsigned int supplement_match_length_limit;
   unsigned int forward_insert_limit;
   unsigned int forward_search_distance;
   unsigned int reduction_passes;
} kc_search_config;

/* Internal allocation and parser counters retained by the fixed encoder. */
typedef struct kc_search_diagnostics {
   unsigned long long match_candidates_retained;
   unsigned long long match_positions_at_limit;
   unsigned long long arrival_candidates_considered;
   unsigned long long arrivals_retained;
   unsigned long long arrivals_discarded_limit;
   unsigned long long arrivals_discarded_duplicate;
   unsigned long long forward_repeat_candidates;
   unsigned long long endpoints_evaluated;
   unsigned long long endpoints_pruned;
   unsigned long long long_match_early_breaks;
   unsigned int max_arrivals_reached;
   unsigned int max_matches_reached;
   size_t allocated_bytes;
} kc_search_diagnostics;

const kc_search_config *kc_search_baseline(void);
int kc_search_validate(const kc_search_config *config,
   char *error, size_t error_size);

#endif
