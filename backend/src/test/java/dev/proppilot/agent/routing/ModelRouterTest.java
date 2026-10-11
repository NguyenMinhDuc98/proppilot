package dev.proppilot.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.ScriptedLlmClient;
import dev.proppilot.agent.llm.offline.OfflineLlmClient;
import dev.proppilot.config.TestLlmProperties;
import org.junit.jupiter.api.Test;

class ModelRouterTest {

    private static final String SMALL = "claude-haiku-4-5-20251001";
    private static final String LARGE = "claude-sonnet-4-5";
    private static final String SIMPLE = "Who is more than 30 days late on rent?";
    private static final String COMPLEX = "Which leases end in the next 60 days and require 90 days notice?";

    private final ComplexityAssessor assessor = new ComplexityAssessor();
    private final ScriptedLlmClient anthropic = new ScriptedLlmClient().servingAs("anthropic", SMALL);

    private ModelRouter router(TestLlmProperties props) {
        return new ModelRouter(anthropic, props.model(SMALL).build(), assessor);
    }

    @Test
    void aComplexQuestionGoesToTheLargerModelWithTheReasonsItScored() {
        var route = router(TestLlmProperties.defaults().largeModel(LARGE)).route(COMPLEX, 0);

        assertThat(route.model()).isEqualTo(LARGE);
        assertThat(route.reason()).isEqualTo(RoutedReason.COMPLEX);
        assertThat(route.complexity().score()).isEqualTo(4);
        assertThat(route.complexity().rules())
                .containsExactly(ComplexityRule.SEVERAL_DATA_AREAS, ComplexityRule.WINDOW_WITH_LEASE_TERM);
    }

    @Test
    void aSimpleQuestionStaysOnTheDefaultModelAndKeepsItsScore() {
        var route = router(TestLlmProperties.defaults().largeModel(LARGE)).route(SIMPLE, 0);

        assertThat(route.model()).isEqualTo(SMALL);
        assertThat(route.reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(route.complexity().score()).isZero();
    }

    @Test
    void aQuestionJustBelowTheThresholdStaysAndOneAtItGoes() {
        var props = TestLlmProperties.defaults().largeModel(LARGE).complexityThreshold(3);
        var twoPoints = "Which units are vacant and who is overdue on rent?";
        var threePoints = "Which units are vacant and who is overdue on rent? Compare them for each building";

        assertThat(router(props).route(twoPoints, 0).reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(router(props).route(threePoints, 0).reason()).isEqualTo(RoutedReason.COMPLEX);
    }

    @Test
    void theThresholdIsConfigurable() {
        var props = TestLlmProperties.defaults().largeModel(LARGE).complexityThreshold(1);

        assertThat(router(props).route("Compare building A with building B", 0).model()).isEqualTo(LARGE);
        assertThat(router(props).route(SIMPLE, 0).model()).isEqualTo(SMALL);
    }

    @Test
    void theHistoryOfTheConversationCountsTowardTheScore() {
        var props = TestLlmProperties.defaults().largeModel(LARGE).complexityThreshold(1);

        assertThat(router(props).route(SIMPLE, 6).reason()).isEqualTo(RoutedReason.COMPLEX);
    }

    @Test
    void withoutALargerModelEveryQuestionGoesToTheDefaultAndNothingIsScored() {
        var route = router(TestLlmProperties.defaults()).route(COMPLEX, 0);

        assertThat(route).isEqualTo(ModelRoute.byDefault(SMALL));
        assertThat(route.complexity()).isNull();
    }

    @Test
    void aLargerModelThatIsTheDefaultModelIsNoSecondModel() {
        var route = router(TestLlmProperties.defaults().largeModel(SMALL)).route(COMPLEX, 0);

        assertThat(route).isEqualTo(ModelRoute.byDefault(SMALL));
    }

    @Test
    void theOfflineAssistantIsNeverRoutedWhateverIsConfigured() {
        var offline = new OfflineLlmClient(new ObjectMapper());
        var props = TestLlmProperties.defaults().provider("offline").largeModel(LARGE).build();
        var router = new ModelRouter(offline, props, assessor);

        var route = router.route(COMPLEX, 0);

        assertThat(route).isEqualTo(ModelRoute.byDefault("rule-based"));
        assertThat(router.fallbackFrom(route)).isEmpty();
    }

    @Test
    void aQuestionThatCannotBeScoredGoesToTheDefaultModelInsteadOfFailing() {
        var failing = mock(ComplexityAssessor.class);
        when(failing.assess(anyString(), anyInt())).thenThrow(new NumberFormatException("For input string: \"secret\""));
        var props = TestLlmProperties.defaults().model(SMALL).largeModel(LARGE).build();

        var route = new ModelRouter(anthropic, props, failing).route(COMPLEX, 0);

        assertThat(route).isEqualTo(ModelRoute.byDefault(SMALL));
        assertThat(route.complexity()).isNull();
    }

    @Test
    void aQuestionWithADigitOutsideTheBasicMultilingualPlaneIsRoutedLikeAnyOther() {
        var router = router(TestLlmProperties.defaults().largeModel(LARGE));
        var fiveInMathematicalDigits = new String(Character.toChars(0x1D7D3));

        var route = router.route("Which leases end within " + fiveInMathematicalDigits + " days?", 0);

        assertThat(route.reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(route.complexity()).isNotNull();
    }

    @Test
    void aTurnThatNeedsNoChoiceTakesTheDefaultModel() {
        var route = router(TestLlmProperties.defaults().largeModel(LARGE)).defaultRoute();

        assertThat(route).isEqualTo(ModelRoute.byDefault(SMALL));
    }

    @Test
    void theFallbackOfTheDefaultModelIsTheLargerOneAndKeepsTheScore() {
        var router = router(TestLlmProperties.defaults().largeModel(LARGE));
        var first = router.route(SIMPLE, 0);

        var fallback = router.fallbackFrom(first).orElseThrow();

        assertThat(fallback.model()).isEqualTo(LARGE);
        assertThat(fallback.reason()).isEqualTo(RoutedReason.FALLBACK);
        assertThat(fallback.complexity()).isEqualTo(first.complexity());
    }

    @Test
    void theFallbackOfTheLargerModelIsTheDefaultOne() {
        var router = router(TestLlmProperties.defaults().largeModel(LARGE));

        var fallback = router.fallbackFrom(router.route(COMPLEX, 0)).orElseThrow();

        assertThat(fallback.model()).isEqualTo(SMALL);
        assertThat(fallback.reason()).isEqualTo(RoutedReason.FALLBACK);
    }

    @Test
    void thereIsNoFallbackWithoutASecondModel() {
        var router = router(TestLlmProperties.defaults());

        assertThat(router.fallbackFrom(router.route(SIMPLE, 0))).isEmpty();
    }
}
