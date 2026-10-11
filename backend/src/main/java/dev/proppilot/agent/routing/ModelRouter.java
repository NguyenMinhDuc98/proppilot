package dev.proppilot.agent.routing;

import dev.proppilot.agent.llm.AnthropicLlmClient;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.config.LlmProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides which model a run goes to, before it starts, and which one it falls back to. The default model answers,
 * unless a larger model is configured and the question scores as multi-step or complex. Without a larger model, or
 * with a provider that has only one (the offline assistant), nothing is scored and every run goes to the default. An
 * answer that the grounding guardrail asks to have again goes to the larger model when there is one.
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final LlmClient llm;
    private final LlmProperties props;
    private final ComplexityAssessor assessor;

    public ModelRouter(LlmClient llm, LlmProperties props, ComplexityAssessor assessor) {
        this.llm = llm;
        this.props = props;
        this.assessor = assessor;
    }

    private boolean enabled() {
        return AnthropicLlmClient.PROVIDER.equals(llm.provider()) && props.hasLargeModel();
    }

    /** The model for a turn that has no choice to make, such as the confirmation of a decision. */
    public ModelRoute defaultRoute() {
        return ModelRoute.byDefault(llm.model());
    }

    /**
     * @param earlierMessages how many messages of the conversation come before the question
     */
    public ModelRoute route(String question, int earlierMessages) {
        if (!enabled()) {
            return defaultRoute();
        }
        var scored = score(question, earlierMessages);
        if (scored.isEmpty()) {
            return defaultRoute();
        }
        var complexity = scored.get();
        boolean complex = complexity.score() >= props.complexityThreshold();
        log.debug("Question scored {} (a score of {} goes to the larger model): {}", complexity.score(),
                props.complexityThreshold(), complexity.rules());
        var reason = complex ? RoutedReason.COMPLEX : RoutedReason.DEFAULT;
        return new ModelRoute(complex ? props.largeModel() : llm.model(), reason, complexity);
    }

    /** Scoring is a heuristic and must never fail a question: whatever goes wrong in it, the default model answers. */
    private Optional<Complexity> score(String question, int earlierMessages) {
        try {
            return Optional.of(assessor.assess(question, earlierMessages));
        } catch (RuntimeException e) {
            // The class only: the message of an exception can quote the question.
            log.warn("A question could not be scored ({}), so the default model takes it", e.getClass().getName());
            return Optional.empty();
        }
    }

    /**
     * Where the run goes to answer again after the grounding guardrail found the answer of {@code current} wanting:
     * the larger model when one is configured, and otherwise the model it already is on.
     */
    public ModelRoute retryFrom(ModelRoute current) {
        return enabled() ? current.retryingOn(props.largeModel()) : current;
    }

    /** The route to take after a call on {@code failed} could not be answered; empty when there is no other model. */
    public Optional<ModelRoute> fallbackFrom(ModelRoute failed) {
        if (!enabled()) {
            return Optional.empty();
        }
        var other = failed.model().equals(llm.model()) ? props.largeModel() : llm.model();
        return Optional.of(failed.fallingBackTo(other));
    }
}
