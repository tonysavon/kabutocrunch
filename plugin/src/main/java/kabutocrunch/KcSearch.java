/*
 * Fixed Salvador 1.4.2 search profile and its validation.
 *
 * Java port of src/kc_search.c / src/kc_search.h.
 */
package kabutocrunch;

final class KcSearch {

    /** Port of kc_search_config. */
    public static final class Config {
        public String name;
        public int maxArrivalsPerPosition;
        public int forwardArrivals;
        public int maxMatchesPerPosition;
        public int forwardRepeatDepth;
        public int exhaustiveLengthThreshold;
        public int enableLongMatchEarlyBreak;
        public int longMatchBreakThreshold;
        public int remainingMatchThreshold;
        public int initialSupplementMatches;
        public int supplementTriggerLength;
        public int supplementMatchLengthLimit;
        public int forwardInsertLimit;
        public int forwardSearchDistance;
        public int reductionPasses;

        public Config() {
        }

        public Config(Config other) {
            this.name = other.name;
            this.maxArrivalsPerPosition = other.maxArrivalsPerPosition;
            this.forwardArrivals = other.forwardArrivals;
            this.maxMatchesPerPosition = other.maxMatchesPerPosition;
            this.forwardRepeatDepth = other.forwardRepeatDepth;
            this.exhaustiveLengthThreshold = other.exhaustiveLengthThreshold;
            this.enableLongMatchEarlyBreak = other.enableLongMatchEarlyBreak;
            this.longMatchBreakThreshold = other.longMatchBreakThreshold;
            this.remainingMatchThreshold = other.remainingMatchThreshold;
            this.initialSupplementMatches = other.initialSupplementMatches;
            this.supplementTriggerLength = other.supplementTriggerLength;
            this.supplementMatchLengthLimit = other.supplementMatchLengthLimit;
            this.forwardInsertLimit = other.forwardInsertLimit;
            this.forwardSearchDistance = other.forwardSearchDistance;
            this.reductionPasses = other.reductionPasses;
        }
    }

    /** Port of kc_search_diagnostics (only filled when diagnostics are enabled). */
    public static final class Diagnostics {
        public long matchCandidatesRetained;
        public long matchPositionsAtLimit;
        public long arrivalCandidatesConsidered;
        public long arrivalsRetained;
        public long arrivalsDiscardedLimit;
        public long arrivalsDiscardedDuplicate;
        public long forwardRepeatCandidates;
        public long endpointsEvaluated;
        public long endpointsPruned;
        public long longMatchEarlyBreaks;
        public int maxArrivalsReached;
        public int maxMatchesReached;
        public long allocatedBytes;
    }

    /* Salvador 1.4.2's fixed approximation settings. */
    private static final Config BASELINE_PROFILE = createBaseline();

    private static Config createBaseline() {
        Config profile = new Config();
        profile.name = "baseline";
        profile.maxArrivalsPerPosition = 109;
        profile.forwardArrivals = 40;
        profile.maxMatchesPerPosition = 78;
        profile.forwardRepeatDepth = 9;
        profile.exhaustiveLengthThreshold = 340;
        profile.enableLongMatchEarlyBreak = 1;
        profile.longMatchBreakThreshold = 1280;
        profile.remainingMatchThreshold = 512;
        profile.initialSupplementMatches = 16;
        profile.supplementTriggerLength = 8;
        profile.supplementMatchLengthLimit = 130;
        profile.forwardInsertLimit = 10;
        profile.forwardSearchDistance = 3;
        profile.reductionPasses = 20;
        return profile;
    }

    public static Config baseline() {
        return BASELINE_PROFILE;
    }

    private KcSearch() {
    }
}
