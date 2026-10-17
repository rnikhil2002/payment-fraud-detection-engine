package com.nikhilr.fraud.scoring;

import com.nikhilr.fraud.config.FraudProperties;
import com.nikhilr.fraud.model.GraphSignals;
import com.nikhilr.fraud.model.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Calls the Python scikit-learn scorer. A short timeout keeps the engine fast; if the
 * scorer is slow or down we return empty and the decision falls back to graph rules.
 */
@Component
public class MlScoringClient {

    private static final Logger log = LoggerFactory.getLogger(MlScoringClient.class);

    private final RestClient client;
    private final boolean enabled;

    public MlScoringClient(FraudProperties properties) {
        FraudProperties.Scorer scorer = properties.scorer();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(scorer.timeoutMs());
        factory.setReadTimeout(scorer.timeoutMs());
        this.client = RestClient.builder()
                .baseUrl(scorer.url())
                .requestFactory(factory)
                .build();
        this.enabled = scorer.enabled();
    }

    public Optional<Double> score(PaymentEvent event, GraphSignals signals) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            ScoreResponse response = client.post()
                    .uri("/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("features", features(event, signals)))
                    .retrieve()
                    .body(ScoreResponse.class);
            return Optional.ofNullable(response).map(ScoreResponse::probability);
        } catch (RuntimeException ex) {
            log.debug("ML scorer unavailable, using graph rules only: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Feature names must match scorer/app/features.py. */
    static Map<String, Object> features(PaymentEvent e, GraphSignals s) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("amount", e.amount());
        f.put("hour_of_day", Instant.ofEpochMilli(e.timestamp()).atZone(ZoneOffset.UTC).getHour());
        f.put("is_transfer", e.isTransfer() ? 1 : 0);
        f.put("new_device", s.newDevice() ? 1 : 0);
        f.put("accounts_on_device", s.accountsOnDevice());
        f.put("devices_for_account_24h", s.devicesForAccount24h());
        f.put("tx_last_10m", s.txLast10m());
        f.put("device_ring_size", s.deviceRingSize());
        f.put("flagged_neighbors", s.flaggedNeighbors());
        f.put("mule_chain_depth", s.muleChainDepth());
        f.put("pass_through_ratio", s.passThroughRatio(e.amount()));
        return f;
    }

    public record ScoreResponse(double probability, String modelVersion) {
    }
}
