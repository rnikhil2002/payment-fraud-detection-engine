package com.nikhilr.fraud.model;

import java.util.List;

/**
 * Final verdict for one payment.
 *
 * @param mlScore       probability from the Python model, or null if the scorer was unavailable
 * @param graphScore    sum of graph rule weights, capped at 1.0
 * @param riskScore     combined score used for the decision
 * @param processingMs  time spent inside the engine for this payment
 */
public record RiskAssessment(
        String txId,
        String accountId,
        PaymentType type,
        double amount,
        String deviceId,
        String merchantId,
        String toAccount,
        Double mlScore,
        double graphScore,
        double riskScore,
        Decision decision,
        List<RuleHit> ruleHits,
        GraphSignals signals,
        long timestamp,
        long processingMs) {

    public boolean isAlert() {
        return decision != Decision.ALLOW;
    }
}
