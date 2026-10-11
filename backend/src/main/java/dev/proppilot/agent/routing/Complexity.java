package dev.proppilot.agent.routing;

import java.util.List;

/** How much work a question looks like: the rules it met, in the order of {@link ComplexityRule}. */
public record Complexity(List<ComplexityRule> rules) {

    public Complexity {
        rules = List.copyOf(rules);
    }

    public int score() {
        return rules.stream().mapToInt(ComplexityRule::points).sum();
    }
}
