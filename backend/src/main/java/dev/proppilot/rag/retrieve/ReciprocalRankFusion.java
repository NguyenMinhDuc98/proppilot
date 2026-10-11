package dev.proppilot.rag.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion: a chunk scores the sum, over the lists it is in, of {@code 1 / (60 + rank)}, with the rank
 * counted from 1. It needs no common scale between the lists, which cosine distances and text search ranks do not
 * have, and a chunk that several lists agree on beats one that a single list likes a lot.
 *
 * <p>Equal scores are ordered by the number of lists the chunk is in (more first), then by document code and by
 * ordinal, so the same lists always give the same order.
 */
final class ReciprocalRankFusion {

    static final int K = 60;
    /** Scores closer than this are equal: the same ranks added up in another order. */
    private static final double RESOLUTION = 1e12;

    /** @param lists the lists the chunk is in, in the order of {@link CandidateList} */
    record Fused(Candidate candidate, double score, List<CandidateList> lists) {
    }

    private static final Comparator<Fused> BEST_FIRST = Comparator
            .comparingLong((Fused fused) -> Math.round(fused.score() * RESOLUTION)).reversed()
            .thenComparing(Comparator.comparingInt((Fused fused) -> fused.lists().size()).reversed())
            .thenComparing(fused -> fused.candidate().documentCode())
            .thenComparingInt(fused -> fused.candidate().ordinal());

    private static final class Sum {
        private final Candidate candidate;
        private final List<CandidateList> lists = new ArrayList<>();
        private double score;

        private Sum(Candidate candidate) {
            this.candidate = candidate;
        }
    }

    private ReciprocalRankFusion() {
    }

    /**
     * @param ranked each list, best first; a list that was not searched is absent
     * @param limit how many chunks to keep
     */
    static List<Fused> fuse(Map<CandidateList, List<Candidate>> ranked, int limit) {
        var sums = new LinkedHashMap<Long, Sum>();
        // The lists are added in the order of the enum, so a chunk's score is always the same sum of the same terms.
        for (var list : CandidateList.values()) {
            var candidates = ranked.getOrDefault(list, List.of());
            for (int index = 0; index < candidates.size(); index++) {
                var candidate = candidates.get(index);
                var sum = sums.computeIfAbsent(candidate.id(), id -> new Sum(candidate));
                sum.score += 1.0 / (K + index + 1);
                sum.lists.add(list);
            }
        }
        return sums.values().stream()
                .map(sum -> new Fused(sum.candidate, sum.score, List.copyOf(sum.lists)))
                .sorted(BEST_FIRST)
                .limit(limit)
                .toList();
    }
}
