package com.nikhilr.fraud.service;

import com.nikhilr.fraud.model.RiskAssessment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Keeps the most recent alerts in memory for the REST API and for new dashboard sessions. */
@Component
public class AlertStore {

    private static final int CAPACITY = 500;

    private final Deque<RiskAssessment> alerts = new ConcurrentLinkedDeque<>();

    public void add(RiskAssessment alert) {
        alerts.addFirst(alert);
        while (alerts.size() > CAPACITY) {
            alerts.pollLast();
        }
    }

    public List<RiskAssessment> latest(int limit) {
        List<RiskAssessment> result = new ArrayList<>();
        for (RiskAssessment alert : alerts) {
            if (result.size() >= limit) {
                break;
            }
            result.add(alert);
        }
        return result;
    }
}
