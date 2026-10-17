package com.nikhilr.fraud.web;

import com.nikhilr.fraud.graph.GraphRepository;
import com.nikhilr.fraud.graph.GraphView;
import com.nikhilr.fraud.model.PaymentEvent;
import com.nikhilr.fraud.model.RiskAssessment;
import com.nikhilr.fraud.service.AlertStore;
import com.nikhilr.fraud.service.DecisionStats;
import com.nikhilr.fraud.service.FraudDetectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class FraudController {

    private final FraudDetectionService service;
    private final AlertStore alertStore;
    private final DecisionStats stats;
    private final GraphRepository graph;

    public FraudController(FraudDetectionService service, AlertStore alertStore,
                           DecisionStats stats, GraphRepository graph) {
        this.service = service;
        this.alertStore = alertStore;
        this.stats = stats;
        this.graph = graph;
    }

    /** Score a single payment synchronously. Handy for demos and tests; Kafka is the main path. */
    @PostMapping("/transactions")
    public RiskAssessment score(@Valid @RequestBody PaymentEvent event) {
        return service.process(event);
    }

    @GetMapping("/alerts")
    public List<RiskAssessment> alerts(@RequestParam(defaultValue = "50") int limit) {
        return alertStore.latest(Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/stats")
    public DecisionStats.Snapshot stats() {
        return stats.snapshot();
    }

    @GetMapping("/graph/accounts/{accountId}")
    public GraphView accountGraph(@PathVariable String accountId,
                                  @RequestParam(defaultValue = "150") int limit) {
        return graph.neighborhood(accountId, Math.min(Math.max(limit, 1), 500));
    }
}
