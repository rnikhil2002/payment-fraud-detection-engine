package com.nikhilr.fraud.rules;

import com.nikhilr.fraud.config.FraudProperties;
import com.nikhilr.fraud.model.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskCombinerTest {

    private final RiskCombiner combiner = new RiskCombiner(new FraudProperties(null, null, null));

    @Test
    void graphOnlyWhenModelIsUnavailable() {
        assertThat(combiner.combine(null, 0.5)).isEqualTo(0.5);
    }

    @Test
    void blendsModelAndGraph() {
        assertThat(combiner.combine(0.9, 0.3)).isEqualTo(0.6);
    }

    @Test
    void strongGraphSignalIsNotDilutedByALowModelScore() {
        assertThat(combiner.combine(0.05, 0.8)).isEqualTo(0.8);
    }

    @Test
    void thresholdsMapToDecisions() {
        assertThat(combiner.decide(0.10)).isEqualTo(Decision.ALLOW);
        assertThat(combiner.decide(0.45)).isEqualTo(Decision.REVIEW);
        assertThat(combiner.decide(0.74)).isEqualTo(Decision.REVIEW);
        assertThat(combiner.decide(0.75)).isEqualTo(Decision.BLOCK);
    }
}
