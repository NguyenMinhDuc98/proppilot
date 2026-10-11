package dev.proppilot.rag.retrieve;

import static dev.proppilot.rag.retrieve.CandidateList.FULL_TEXT;
import static dev.proppilot.rag.retrieve.CandidateList.TRIGRAM;
import static dev.proppilot.rag.retrieve.CandidateList.VECTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import dev.proppilot.rag.retrieve.ReciprocalRankFusion.Fused;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    private static Candidate chunk(long id, String code, int ordinal) {
        return new Candidate(id, code, ordinal);
    }

    private static List<Long> ids(List<Fused> fused) {
        return fused.stream().map(f -> f.candidate().id()).toList();
    }

    private static Map<CandidateList, List<Candidate>> lists(List<Candidate> vector, List<Candidate> fullText,
                                                             List<Candidate> trigram) {
        var lists = new EnumMap<CandidateList, List<Candidate>>(CandidateList.class);
        lists.put(VECTOR, vector);
        lists.put(FULL_TEXT, fullText);
        lists.put(TRIGRAM, trigram);
        return lists;
    }

    @Test
    void aChunkScoresTheSumOfOneOverSixtyPlusItsRankInEachListItIsIn() {
        var a = chunk(1, "LSE-A-1", 0);
        var b = chunk(2, "LSE-A-1", 1);

        var fused = ReciprocalRankFusion.fuse(lists(List.of(a, b), List.of(b), List.of()), 10);

        var scores = fused.stream().collect(Collectors.toMap(f -> f.candidate().id(), Fused::score));
        assertThat(scores.get(1L)).isCloseTo(1.0 / 61, offset(1e-12));
        assertThat(scores.get(2L)).isCloseTo(1.0 / 62 + 1.0 / 61, offset(1e-12));
    }

    @Test
    void aChunkThatTwoListsAgreeOnBeatsTheTopOfOneList() {
        var top = chunk(1, "LSE-A-1", 0);
        var agreed = chunk(2, "LSE-A-1", 1);

        var fused = ReciprocalRankFusion.fuse(lists(List.of(top, agreed), List.of(agreed), List.of()), 10);

        assertThat(ids(fused)).containsExactly(2L, 1L);
        assertThat(fused.get(0).lists()).containsExactly(VECTOR, FULL_TEXT);
        assertThat(fused.get(1).lists()).containsExactly(VECTOR);
    }

    @Test
    void aListThatWasNotSearchedIsSimplyAbsent() {
        var only = chunk(1, "LSE-A-1", 0);
        var ranked = new EnumMap<CandidateList, List<Candidate>>(CandidateList.class);
        ranked.put(FULL_TEXT, List.of(only));

        var fused = ReciprocalRankFusion.fuse(ranked, 10);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).lists()).containsExactly(FULL_TEXT);
        assertThat(ReciprocalRankFusion.fuse(new EnumMap<>(CandidateList.class), 10)).isEmpty();
    }

    @Test
    void equalScoresAreOrderedByDocumentCodeAndThenOrdinal() {
        var later = chunk(1, "LSE-B-2", 0);
        var sameDocumentLaterClause = chunk(2, "LSE-A-9", 5);
        var sameDocumentEarlierClause = chunk(3, "LSE-A-9", 2);
        // Each is first in its own list, so all three score one over sixty-one.
        var fused = ReciprocalRankFusion.fuse(
                lists(List.of(later), List.of(sameDocumentLaterClause), List.of(sameDocumentEarlierClause)), 10);

        assertThat(ids(fused)).containsExactly(3L, 2L, 1L);
        assertThat(fused).extracting(Fused::score).containsOnly(1.0 / 61);
    }

    @Test
    void theSameRanksAddedUpInAnotherOrderAreATieAndNotAWinForTheRoundingError() {
        // 1/61 + 1/62 + 1/67 added in this order and in the order of y's ranks differ in the last bit of a double.
        var x = chunk(1, "LSE-B-2", 0);
        var y = chunk(2, "LSE-B-1", 0);
        var vector = List.of(x, chunk(10, "LSE-Z-1", 0), chunk(11, "LSE-Z-1", 1), chunk(12, "LSE-Z-1", 2),
                chunk(13, "LSE-Z-1", 3), chunk(14, "LSE-Z-1", 4), y);
        var fullText = List.of(y, x);
        var trigram = List.of(chunk(20, "LSE-Z-2", 0), y, chunk(21, "LSE-Z-2", 1), chunk(22, "LSE-Z-2", 2),
                chunk(23, "LSE-Z-2", 3), chunk(24, "LSE-Z-2", 4), x);

        var fused = ReciprocalRankFusion.fuse(lists(vector, fullText, trigram), 20);

        var scoreOfX = fused.stream().filter(f -> f.candidate().id() == 1).findFirst().orElseThrow().score();
        var scoreOfY = fused.stream().filter(f -> f.candidate().id() == 2).findFirst().orElseThrow().score();
        assertThat(scoreOfX).as("x scores one bit more than y").isGreaterThan(scoreOfY);
        assertThat(ids(fused).subList(0, 2)).as("but they are equal, so y comes first by its code")
                .containsExactly(2L, 1L);
    }

    @Test
    void onlyTheBestChunksAreKept() {
        var vector = List.of(chunk(1, "LSE-A-1", 0), chunk(2, "LSE-A-1", 1), chunk(3, "LSE-A-1", 2));

        var fused = ReciprocalRankFusion.fuse(lists(vector, List.of(), List.of()), 2);

        assertThat(ids(fused)).containsExactly(1L, 2L);
    }

    @Test
    void theSameListsAlwaysGiveTheSameOrder() {
        var a = chunk(1, "LSE-A-1", 0);
        var b = chunk(2, "LSE-A-2", 0);
        var c = chunk(3, "LSE-A-3", 0);

        var first = ids(ReciprocalRankFusion.fuse(lists(List.of(a, b), List.of(c, a), List.of(b, c)), 10));
        var second = ids(ReciprocalRankFusion.fuse(lists(List.of(a, b), List.of(c, a), List.of(b, c)), 10));

        assertThat(first).isEqualTo(second);
    }
}
