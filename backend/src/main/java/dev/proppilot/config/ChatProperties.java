package dev.proppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proppilot.chat")
public record ChatProperties(int maxMessageLength, int rateLimitPerMinute) {
}
