package com.nikhilr.fraud.rules;

import com.nikhilr.fraud.model.GraphSignals;
import com.nikhilr.fraud.model.PaymentEvent;
import com.nikhilr.fraud.model.RuleHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Graph based fraud rules. Pure logic with no I/O so it is easy to unit test.
 * <p>
 * Each rule adds a weight; the graph score is the sum capped at 1.0.
 */
@Component
public class RuleEngine {

    public static final String SHARED_DEVICE = "SHARED_DEVICE";
    public static final String DEVICE_RING = "DEVICE_RING";
    public static final String FLAGGED_NEIGHBOR = "FLAGGED_NEIGHBOR";
    public static final String MULE_CHAIN = "MULE_CHAIN";
    public static final String PASS_THROUGH = "PASS_THROUGH";
    public static final String VELOCITY = "VELOCITY";
    public static final String NEW_DEVICE_HIGH_AMOUNT = "NEW_DEVICE_HIGH_AMOUNT";
    public static final String RISKY_MERCHANT = "RISKY_MERCHANT";
    public static final String DEVICE_HOPPING = "DEVICE_HOPPING";

    public List<RuleHit> evaluate(PaymentEvent event, GraphSignals s) {
        List<RuleHit> hits = new ArrayList<>();

        // Many accounts on one phone or laptop is the classic fraud ring footprint.
        if (s.accountsOnDevice() >= 6) {
            hits.add(new RuleHit(SHARED_DEVICE, 0.50,
                    s.accountsOnDevice() + " accounts share device " + event.deviceId()));
        } else if (s.accountsOnDevice() >= 4) {
            hits.add(new RuleHit(SHARED_DEVICE, 0.30,
                    s.accountsOnDevice() + " accounts share device " + event.deviceId()));
        }

        if (s.deviceRingSize() >= 8) {
            hits.add(new RuleHit(DEVICE_RING, 0.20,
                    "linked to " + s.deviceRingSize() + " accounts through shared devices"));
        }

        if (s.flaggedNeighbors() >= 1) {
            hits.add(new RuleHit(FLAGGED_NEIGHBOR, 0.35,
                    "shares a device with " + s.flaggedNeighbors() + " blocked account(s)"));
        }

        // Money mule: funds arrive and are pushed on to the next account within the hour.
        if (event.isTransfer()) {
            double ratio = s.passThroughRatio(event.amount());
            if (s.muleChainDepth() >= 2 && ratio >= 0.7) {
                hits.add(new RuleHit(MULE_CHAIN, 0.50, String.format(
                        "part of a %d-hop transfer chain, forwarding %.0f%% of money just received",
                        s.muleChainDepth() + 1, ratio * 100)));
            } else if (s.muleChainDepth() >= 1 && ratio >= 0.9) {
                hits.add(new RuleHit(PASS_THROUGH, 0.25, String.format(
                        "forwarding %.0f%% of money received in the last hour", ratio * 100)));
            }
        }

        if (s.txLast10m() >= 10) {
            hits.add(new RuleHit(VELOCITY, 0.30, s.txLast10m() + " payments in 10 minutes"));
        } else if (s.txLast10m() >= 6) {
            hits.add(new RuleHit(VELOCITY, 0.15, s.txLast10m() + " payments in 10 minutes"));
        }

        if (s.newDevice() && event.amount() >= 2000) {
            hits.add(new RuleHit(NEW_DEVICE_HIGH_AMOUNT, 0.25, String.format(
                    "%.2f sent from a device never seen for this account", event.amount())));
        }

        if (event.hasMerchant() && s.merchantFlaggedPayers() >= 2) {
            hits.add(new RuleHit(RISKY_MERCHANT, 0.20,
                    "merchant was paid by " + s.merchantFlaggedPayers() + " blocked accounts"));
        }

        if (s.devicesForAccount24h() >= 4) {
            hits.add(new RuleHit(DEVICE_HOPPING, 0.20,
                    s.devicesForAccount24h() + " devices used in 24h"));
        }

        return hits;
    }

    public double graphScore(List<RuleHit> hits) {
        double sum = hits.stream().mapToDouble(RuleHit::weight).sum();
        return Math.min(1.0, sum);
    }
}
