package com.nikhilr.fraud.rules;

import com.nikhilr.fraud.config.FraudProperties;
import com.nikhilr.fraud.model.Decision;
import org.springframework.stereotype.Component;

/**
 * Blends the ML probability with the graph score and maps the result to a decision.
 * Graph rules are explainable, so a strong graph signal is never watered down by the model.
 */
@Component
public class RiskCombiner {

    private final double reviewThreshold;
    private final double blockThreshold;

    public RiskCombiner(FraudProperties properties) {
        this.reviewThreshold = properties.thresholds().review();
        this.blockThreshold = properties.thresholds().block();
    }

    public double combine(Double mlScore, double graphScore) {
        if (mlScore == null) {
            return round(graphScore);
        }
        double blended = 0.5 * mlScore + 0.5 * graphScore;
        return round(Math.max(graphScore, blended));
    }

    public Decision decide(double riskScore) {
        if (riskScore >= blockThreshold) {
            return Decision.BLOCK;
        }
        if (riskScore >= reviewThreshold) {
            return Decision.REVIEW;
        }
        return Decision.ALLOW;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
