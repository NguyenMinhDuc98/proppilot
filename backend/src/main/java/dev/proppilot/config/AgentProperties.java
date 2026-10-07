package dev.proppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proppilot.agent")
public record AgentProperties(int maxIterations, int maxHistoryTurns) {
}
