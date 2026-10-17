package com.nikhilr.fraud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nikhilr.fraud.config.FraudProperties;
import com.nikhilr.fraud.graph.GraphRepository;
import com.nikhilr.fraud.model.Decision;
import com.nikhilr.fraud.model.GraphSignals;
import com.nikhilr.fraud.model.PaymentEvent;
import com.nikhilr.fraud.model.PaymentType;
import com.nikhilr.fraud.model.RiskAssessment;
import com.nikhilr.fraud.rules.RiskCombiner;
import com.nikhilr.fraud.rules.RuleEngine;
import com.nikhilr.fraud.scoring.MlScoringClient;
import com.nikhilr.fraud.web.AlertBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudDetectionServiceTest {

    private GraphRepository graph;
    private MlScoringClient ml;
    private AlertBroadcaster broadcaster;
    private KafkaTemplate<String, String> kafka;
    private AlertStore alertStore;
    private DecisionStats stats;
    private FraudDetectionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        graph = mock(GraphRepository.class);
        ml = mock(MlScoringClient.class);
        broadcaster = mock(AlertBroadcaster.class);
        kafka = mock(KafkaTemplate.class);
        alertStore = new AlertStore();
        stats = new DecisionStats(new SimpleMeterRegistry());
        FraudProperties props = new FraudProperties(null, null, null);
        service = new FraudDetectionService(graph, new RuleEngine(), new RiskCombiner(props), ml,
                alertStore, stats, broadcaster, kafka, new ObjectMapper(), props);
    }

    private static PaymentEvent payment(String account, String device, double amount) {
        return new PaymentEvent("tx-" + account, PaymentType.CARD, account, null, "m-1", device,
                "10.0.0.1", amount, "USD", "US", 1_700_000_000_000L);
    }

    @Test
    void cleanPaymentIsAllowedWithoutAnAlert() {
        PaymentEvent event = payment("acc-1", "dev-1", 25);
        when(graph.recordPayment(event)).thenReturn(false);
        when(graph.computeSignals(event, false)).thenReturn(GraphSignals.empty());
        when(ml.score(any(), any())).thenReturn(Optional.of(0.02));

        RiskAssessment result = service.process(event);

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(alertStore.latest(10)).isEmpty();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
        assertThat(stats.snapshot().allowed()).isEqualTo(1);
    }

    @Test
    void deviceRingWithHighModelScoreIsBlockedAndFlagged() {
        PaymentEvent event = payment("acc-7", "dev-ring", 900);
        GraphSignals ring = new GraphSignals(7, 1, 1, 9, 0, 0, 0, 0, true);
        when(graph.recordPayment(event)).thenReturn(true);
        when(graph.computeSignals(event, true)).thenReturn(ring);
        when(ml.score(any(), any())).thenReturn(Optional.of(0.97));

        RiskAssessment result = service.process(event);

        // graph: SHARED_DEVICE 0.5 + DEVICE_RING 0.2 = 0.7, blended with 0.97 -> 0.835
        assertThat(result.graphScore()).isCloseTo(0.7, within(1e-9));
        assertThat(result.riskScore()).isEqualTo(0.835);
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(alertStore.latest(10)).hasSize(1);
        verify(broadcaster).broadcast(eq("alert"), any(RiskAssessment.class));
        verify(kafka).send(eq("fraud-alerts"), eq("acc-7"), anyString());
        verify(graph).flagAccount(eq("acc-7"), anyString(), anyLong());
    }

    @Test
    void worksOnGraphRulesAloneWhenScorerIsDown() {
        PaymentEvent event = payment("acc-3", "dev-3", 30);
        GraphSignals sixOnDevice = new GraphSignals(6, 1, 1, 5, 0, 0, 0, 0, false);
        when(graph.recordPayment(event)).thenReturn(false);
        when(graph.computeSignals(event, false)).thenReturn(sixOnDevice);
        when(ml.score(any(), any())).thenReturn(Optional.empty());

        RiskAssessment result = service.process(event);

        assertThat(result.mlScore()).isNull();
        assertThat(result.decision()).isEqualTo(Decision.REVIEW);
        verify(graph, never()).flagAccount(anyString(), anyString(), anyLong());
    }

    @Test
    void missingTimestampIsFilledIn() {
        PaymentEvent event = new PaymentEvent("tx-9", PaymentType.UPI, "acc-9", null, "m-2", "dev-9",
                null, 10, "INR", "IN", 0);
        when(graph.recordPayment(any())).thenReturn(false);
        when(graph.computeSignals(any(), anyBoolean())).thenReturn(GraphSignals.empty());
        when(ml.score(any(), any())).thenReturn(Optional.empty());

        RiskAssessment result = service.process(event);

        assertThat(result.timestamp()).isPositive();
    }
}
