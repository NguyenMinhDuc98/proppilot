package dev.proppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Admin access. The token is optional: with none set, the admin endpoints do not exist. */
@ConfigurationProperties(prefix = "proppilot.admin")
public record AdminProperties(String token) {

    public AdminProperties {
        token = token == null ? "" : token.strip();
    }

    public boolean enabled() {
        return !token.isEmpty();
    }

    /** Keeps the secret out of any log line or error message that prints the properties. */
    @Override
    public String toString() {
        return "AdminProperties[enabled=" + enabled() + "]";
    }
}
