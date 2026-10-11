package dev.proppilot.config;

import dev.proppilot.guardrail.GuardrailMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The grounding guardrail. {@code off} checks nothing, {@code warn} (the default) flags an answer that states a figure,
 * a code, a name or a citation that nothing in the run supports, {@code retry} also asks for such an answer once more.
 *
 * @param mode {@code GUARDRAIL_MODE}: {@code off}, {@code warn} or {@code retry}; blank is {@code warn}, anything
 *             else stops the backend from starting
 */
@ConfigurationProperties(prefix = "proppilot.guardrail")
public record GuardrailProperties(String mode) {

    public GuardrailProperties {
        mode = GuardrailMode.parse(mode).name();
    }

    public GuardrailMode guardrailMode() {
        return GuardrailMode.valueOf(mode);
    }
}
