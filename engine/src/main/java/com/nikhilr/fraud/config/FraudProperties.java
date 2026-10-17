package com.nikhilr.fraud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable settings for the engine. Everything can be overridden with environment variables,
 * e.g. FRAUD_SCORER_URL or FRAUD_THRESHOLDS_BLOCK.
 */
@ConfigurationProperties(prefix = "fraud")
public record FraudProperties(Topics topics, Scorer scorer, Thresholds thresholds) {

    public FraudProperties {
        if (topics == null) {
            topics = new Topics("payments", "fraud-alerts");
        }
        if (scorer == null) {
            scorer = new Scorer("http://localhost:8000", 400, true);
        }
        if (thresholds == null) {
            thresholds = new Thresholds(0.45, 0.75);
        }
    }

    public record Topics(String payments, String alerts) {
    }

    /** Python ML scorer. If it is down the engine keeps working on graph rules alone. */
    public record Scorer(String url, int timeoutMs, boolean enabled) {
    }

    /** Risk at or above review goes to manual review, at or above block is declined. */
    public record Thresholds(double review, double block) {
    }
}
