package dev.proppilot.agent.routing;

/** Why a run went to the model that answered it. */
public enum RoutedReason {

    /** The default model, which answers unless something says otherwise. */
    DEFAULT,
    /** The larger model: the question scored as multi-step or complex. */
    COMPLEX,
    /** The larger model: the grounding guardrail asked for the answer to be tried again. */
    GUARDRAIL_RETRY,
    /** The other model: the first one was overloaded or timed out. */
    FALLBACK
}
