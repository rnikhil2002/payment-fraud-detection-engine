package com.nikhilr.fraud.rules;

import com.nikhilr.fraud.model.GraphSignals;
import com.nikhilr.fraud.model.PaymentEvent;
import com.nikhilr.fraud.model.PaymentType;
import com.nikhilr.fraud.model.RuleHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private static PaymentEvent card(double amount) {
        return new PaymentEvent("tx-1", PaymentType.CARD, "acc-1", null, "m-1", "dev-1",
                "10.0.0.1", amount, "USD", "US", 1_700_000_000_000L);
    }

    private static PaymentEvent transfer(double amount) {
        return new PaymentEvent("tx-2", PaymentType.TRANSFER, "acc-1", "acc-2", null, "dev-1",
                "10.0.0.1", amount, "USD", "US", 1_700_000_000_000L);
    }

    private static GraphSignals signals(int accountsOnDevice, int devices24h, int tx10m, int ring,
                                        int flagged, int chain, double inbound, int merchantFlagged,
                                        boolean newDevice) {
        return new GraphSignals(accountsOnDevice, devices24h, tx10m, ring, flagged, chain, inbound,
                merchantFlagged, newDevice);
    }

    private static List<String> names(List<RuleHit> hits) {
        return hits.stream().map(RuleHit::rule).toList();
    }

    @Test
    void normalPaymentTriggersNothing() {
        List<RuleHit> hits = engine.evaluate(card(42.50), signals(1, 1, 2, 0, 0, 0, 0, 0, false));
        assertThat(hits).isEmpty();
        assertThat(engine.graphScore(hits)).isZero();
    }

    @Test
    void sixAccountsOnOneDeviceIsAStrongRingSignal() {
        List<RuleHit> hits = engine.evaluate(card(80), signals(6, 1, 1, 5, 0, 0, 0, 0, false));
        assertThat(names(hits)).containsExactly(RuleEngine.SHARED_DEVICE);
        assertThat(engine.graphScore(hits)).isEqualTo(0.50);
    }

    @Test
    void fourAccountsOnOneDeviceIsAWeakerSignal() {
        List<RuleHit> hits = engine.evaluate(card(80), signals(4, 1, 1, 3, 0, 0, 0, 0, false));
        assertThat(engine.graphScore(hits)).isEqualTo(0.30);
    }

    @Test
    void forwardingMostOfReceivedMoneyAlongAChainIsAMule() {
        // Received 5000 in the last hour through a 2-hop chain, now sending 4800 on.
        List<RuleHit> hits = engine.evaluate(transfer(4800), signals(1, 1, 1, 0, 0, 2, 5000, 0, false));
        assertThat(names(hits)).containsExactly(RuleEngine.MULE_CHAIN);
        assertThat(hits.get(0).reason()).contains("3-hop");
    }

    @Test
    void singleHopPassThroughIsWeaker() {
        List<RuleHit> hits = engine.evaluate(transfer(4950), signals(1, 1, 1, 0, 0, 1, 5000, 0, false));
        assertThat(names(hits)).containsExactly(RuleEngine.PASS_THROUGH);
    }

    @Test
    void chainRulesOnlyApplyToTransfers() {
        List<RuleHit> hits = engine.evaluate(card(4800), signals(1, 1, 1, 0, 0, 3, 5000, 0, false));
        assertThat(hits).isEmpty();
    }

    @Test
    void cardTestingBurstTriggersVelocity() {
        List<RuleHit> hits = engine.evaluate(card(1.99), signals(1, 1, 12, 0, 0, 0, 0, 0, false));
        assertThat(names(hits)).containsExactly(RuleEngine.VELOCITY);
        assertThat(engine.graphScore(hits)).isEqualTo(0.30);
    }

    @Test
    void accountTakeoverPattern() {
        List<RuleHit> hits = engine.evaluate(card(2500), signals(1, 4, 1, 0, 0, 0, 0, 0, true));
        assertThat(names(hits)).containsExactlyInAnyOrder(
                RuleEngine.NEW_DEVICE_HIGH_AMOUNT, RuleEngine.DEVICE_HOPPING);
    }

    @Test
    void sharingADeviceWithABlockedAccountIsRisky() {
        List<RuleHit> hits = engine.evaluate(card(30), signals(2, 1, 1, 1, 1, 0, 0, 0, false));
        assertThat(names(hits)).containsExactly(RuleEngine.FLAGGED_NEIGHBOR);
    }

    @Test
    void graphScoreIsCappedAtOne() {
        List<RuleHit> hits = engine.evaluate(card(5000), signals(8, 5, 15, 10, 3, 0, 0, 4, true));
        assertThat(engine.graphScore(hits)).isEqualTo(1.0);
    }
}
