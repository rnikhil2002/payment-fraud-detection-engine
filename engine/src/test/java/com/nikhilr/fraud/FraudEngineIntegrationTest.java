package com.nikhilr.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nikhilr.fraud.graph.GraphView;
import com.nikhilr.fraud.model.Decision;
import com.nikhilr.fraud.model.PaymentEvent;
import com.nikhilr.fraud.model.PaymentType;
import com.nikhilr.fraud.model.RiskAssessment;
import com.nikhilr.fraud.scoring.MlScoringClient;
import com.nikhilr.fraud.service.AlertStore;
import com.nikhilr.fraud.service.FraudDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End to end against real Kafka and Neo4j containers. The ML scorer is mocked out so the
 * test checks the graph rules on their own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FraudEngineIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    @ServiceConnection
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.20"))
            .withAdminPassword("integration-test");

    @MockBean
    MlScoringClient mlScoringClient;

    @Autowired
    FraudDetectionService service;

    @Autowired
    AlertStore alertStore;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    Driver driver;

    @BeforeEach
    void setUp() {
        when(mlScoringClient.score(any(), any())).thenReturn(Optional.empty());
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void detectsADeviceSharedByAFraudRing() {
        String device = id("dev-ring");
        long now = System.currentTimeMillis();
        RiskAssessment last = null;
        for (int i = 0; i < 6; i++) {
            last = service.process(new PaymentEvent(id("tx"), PaymentType.CARD, id("acc"), null,
                    "m-electronics", device, "203.0.113.7", 450, "USD", "US", now + i));
        }
        assertThat(last).isNotNull();
        assertThat(last.signals().accountsOnDevice()).isEqualTo(6);
        assertThat(last.decision()).isNotEqualTo(Decision.ALLOW);
    }

    @Test
    void detectsAMoneyMuleChain() {
        String a = id("acc"), b = id("acc"), c = id("acc"), d = id("acc");
        long now = System.currentTimeMillis();
        service.process(transfer(a, b, 5000, now));
        service.process(transfer(b, c, 4900, now + 60_000));
        RiskAssessment third = service.process(transfer(c, d, 4800, now + 120_000));

        assertThat(third.signals().muleChainDepth()).isEqualTo(2);
        assertThat(third.decision()).isNotEqualTo(Decision.ALLOW);
        assertThat(third.ruleHits()).anyMatch(h -> h.rule().equals("MULE_CHAIN"));
    }

    @Test
    void paymentsSentToKafkaAreScored() throws Exception {
        String device = id("dev-kafka");
        long now = System.currentTimeMillis();
        // Eight accounts on one device; consumers run in parallel, so send a margin above six.
        for (int i = 0; i < 8; i++) {
            PaymentEvent event = new PaymentEvent(id("tx"), PaymentType.UPI, id("acc"), null,
                    "m-wallet", device, "198.51.100.4", 300, "INR", "IN", now + i);
            kafkaTemplate.send("payments", event.fromAccount(), mapper.writeValueAsString(event));
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(alertStore.latest(50)).anyMatch(alert -> device.equals(alert.deviceId())));
    }

    @Test
    void restApiReturnsGraphNeighbourhood() {
        String account = id("acc");
        service.process(new PaymentEvent(id("tx"), PaymentType.CARD, account, null, "m-coffee",
                id("dev"), "192.0.2.1", 4.5, "USD", "US", System.currentTimeMillis()));

        GraphView view = rest.getForObject("/api/graph/accounts/" + account, GraphView.class);

        assertThat(view.nodes()).extracting(GraphView.GraphNode::label)
                .contains("Account", "Device", "Merchant");
        assertThat(view.links()).extracting(GraphView.GraphLink::type).contains("USED", "PAID");
    }

    private static PaymentEvent transfer(String from, String to, double amount, long ts) {
        return new PaymentEvent(id("tx"), PaymentType.TRANSFER, from, to, null, "dev-" + from,
                "192.0.2.10", amount, "USD", "US", ts);
    }
}
