package dev.proppilot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent limits: model rounds per question, remembered chat turns and the wall-clock budget of one run. */
@ConfigurationProperties(prefix = "proppilot.agent")
public record AgentProperties(int maxIterations, int maxHistoryTurns, int runTimeoutSeconds) {

    public Duration runTimeout() {
        return Duration.ofSeconds(runTimeoutSeconds);
    }
}
