package com.nikhilr.fraud.service;

import com.nikhilr.fraud.model.Decision;
import com.nikhilr.fraud.model.RiskAssessment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Running totals for the dashboard, also exported to Prometheus through Micrometer. */
@Component
public class DecisionStats {

    private final Map<Decision, LongAdder> counts = new EnumMap<>(Decision.class);
    private final Map<Decision, Counter> counters = new EnumMap<>(Decision.class);
    private final LongAdder totalLatencyMs = new LongAdder();
    private final AtomicLong maxLatencyMs = new AtomicLong();
    private final Timer timer;

    public DecisionStats(MeterRegistry registry) {
        for (Decision d : Decision.values()) {
            counts.put(d, new LongAdder());
            counters.put(d, Counter.builder("fraud.decisions")
                    .description("Payments scored by decision")
                    .tag("decision", d.name())
                    .register(registry));
        }
        this.timer = Timer.builder("fraud.processing")
                .description("Time to score one payment")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void record(RiskAssessment a) {
        counts.get(a.decision()).increment();
        counters.get(a.decision()).increment();
        totalLatencyMs.add(a.processingMs());
        maxLatencyMs.accumulateAndGet(a.processingMs(), Math::max);
        timer.record(Duration.ofMillis(a.processingMs()));
    }

    public Snapshot snapshot() {
        long allowed = counts.get(Decision.ALLOW).sum();
        long review = counts.get(Decision.REVIEW).sum();
        long blocked = counts.get(Decision.BLOCK).sum();
        long total = allowed + review + blocked;
        double avg = total == 0 ? 0.0 : (double) totalLatencyMs.sum() / total;
        return new Snapshot(total, allowed, review, blocked, Math.round(avg * 10) / 10.0, maxLatencyMs.get());
    }

    public record Snapshot(long total, long allowed, long review, long blocked,
                           double avgLatencyMs, long maxLatencyMs) {
    }
}
