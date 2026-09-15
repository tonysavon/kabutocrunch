#include <string.h>

#include "kc_search.h"

/* Salvador 1.4.2's fixed approximation settings. */
static const kc_search_config baseline_profile = {
   "baseline", 109, 40, 78, 9, 340, 1, 1280, 512, 16, 8, 130, 10, 3, 20
};

const kc_search_config *kc_search_baseline(void) {
   return &baseline_profile;
}

int kc_search_validate(const kc_search_config *config,
      char *error, size_t error_size) {
   if (config != NULL &&
       config->max_arrivals_per_position >= 2 &&
       config->forward_arrivals >= 1 &&
       config->forward_arrivals <= config->max_arrivals_per_position &&
       config->max_matches_per_position >= 2) {
      if (error != NULL && error_size != 0)
         error[0] = '\0';
      return 1;
   }
   if (error != NULL && error_size != 0) {
      const char *message = "invalid fixed search configuration";
      strncpy(error, message, error_size - 1);
      error[error_size - 1] = '\0';
   }
   return 0;
}
