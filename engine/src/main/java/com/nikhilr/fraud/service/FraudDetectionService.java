package com.nikhilr.fraud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nikhilr.fraud.config.FraudProperties;
import com.nikhilr.fraud.graph.GraphRepository;
import com.nikhilr.fraud.model.Decision;
import com.nikhilr.fraud.model.GraphSignals;
import com.nikhilr.fraud.model.PaymentEvent;
import com.nikhilr.fraud.model.RiskAssessment;
import com.nikhilr.fraud.model.RuleHit;
import com.nikhilr.fraud.rules.RiskCombiner;
import com.nikhilr.fraud.rules.RuleEngine;
import com.nikhilr.fraud.scoring.MlScoringClient;
import com.nikhilr.fraud.web.AlertBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The pipeline for one payment: write it to the graph, pull graph signals, run the rules,
 * ask the ML model, combine, and publish an alert if it looks risky.
 */
@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final GraphRepository graph;
    private final RuleEngine rules;
    private final RiskCombiner combiner;
    private final MlScoringClient mlClient;
    private final AlertStore alertStore;
    private final DecisionStats stats;
    private final AlertBroadcaster broadcaster;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String alertsTopic;

    public FraudDetectionService(GraphRepository graph,
                                 RuleEngine rules,
                                 RiskCombiner combiner,
                                 MlScoringClient mlClient,
                                 AlertStore alertStore,
                                 DecisionStats stats,
                                 AlertBroadcaster broadcaster,
                                 KafkaTemplate<String, String> kafka,
                                 ObjectMapper mapper,
                                 FraudProperties properties) {
        this.graph = graph;
        this.rules = rules;
        this.combiner = combiner;
        this.mlClient = mlClient;
        this.alertStore = alertStore;
        this.stats = stats;
        this.broadcaster = broadcaster;
        this.kafka = kafka;
        this.mapper = mapper;
        this.alertsTopic = properties.topics().alerts();
    }

    public RiskAssessment process(PaymentEvent incoming) {
        long started = System.nanoTime();
        PaymentEvent event = incoming.withTimestampIfMissing(System.currentTimeMillis());

        boolean newDevice = graph.recordPayment(event);
        GraphSignals signals = graph.computeSignals(event, newDevice);

        List<RuleHit> hits = rules.evaluate(event, signals);
        double graphScore = rules.graphScore(hits);
        Double mlScore = mlClient.score(event, signals).orElse(null);
        double risk = combiner.combine(mlScore, graphScore);
        Decision decision = combiner.decide(risk);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        RiskAssessment assessment = new RiskAssessment(
                event.txId(), event.fromAccount(), event.type(), event.amount(), event.deviceId(),
                event.merchantId(), event.toAccount(), mlScore, graphScore, risk, decision,
                hits, signals, event.timestamp(), elapsedMs);

        stats.record(assessment);
        if (assessment.isAlert()) {
            publishAlert(assessment);
        }
        if (decision == Decision.BLOCK) {
            String reason = hits.stream().map(RuleHit::rule).collect(Collectors.joining(","));
            graph.flagAccount(event.fromAccount(), reason.isEmpty() ? "ML_SCORE" : reason, event.timestamp());
        }
        return assessment;
    }

    private void publishAlert(RiskAssessment assessment) {
        alertStore.add(assessment);
        broadcaster.broadcast("alert", assessment);
        try {
            kafka.send(alertsTopic, assessment.accountId(), mapper.writeValueAsString(assessment));
        } catch (JsonProcessingException ex) {
            log.error("Could not serialize alert {}", assessment.txId(), ex);
        }
        log.info("{} tx={} account={} risk={} rules={}", assessment.decision(), assessment.txId(),
                assessment.accountId(), assessment.riskScore(),
                assessment.ruleHits().stream().map(RuleHit::rule).toList());
    }
}
