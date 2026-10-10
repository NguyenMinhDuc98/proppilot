package dev.proppilot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Write actions on the public demo: how long a proposal waits for a decision, and the caps that keep strangers from
 * churning the shared data.
 */
@ConfigurationProperties(prefix = "proppilot.actions")
public record ActionProperties(int ttlMinutes, int decisionsPerMinute, int maxApprovalsPerDay) {

    public Duration ttl() {
        return Duration.ofMinutes(ttlMinutes);
    }
}
