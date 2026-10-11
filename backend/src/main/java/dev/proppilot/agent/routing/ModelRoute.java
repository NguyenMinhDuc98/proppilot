package dev.proppilot.agent.routing;

/**
 * Where a run goes and why.
 *
 * @param model the model that takes the next call of the run
 * @param reason why it was chosen
 * @param complexity how the question scored, or null when it was not scored (routing is off, or the turn needed no
 *                   choice)
 */
public record ModelRoute(String model, RoutedReason reason, Complexity complexity) {

    /** A run that goes to the model the client uses, because nothing asks for another. */
    public static ModelRoute byDefault(String model) {
        return new ModelRoute(model, RoutedReason.DEFAULT, null);
    }

    /** The same run, sent on to the other model after the first one failed. */
    ModelRoute fallingBackTo(String model) {
        return new ModelRoute(model, RoutedReason.FALLBACK, complexity);
    }
}
