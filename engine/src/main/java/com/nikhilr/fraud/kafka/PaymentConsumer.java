package com.nikhilr.fraud.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nikhilr.fraud.model.PaymentEvent;
import com.nikhilr.fraud.service.FraudDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reads payment events from Kafka and hands them to the detection pipeline. */
@Component
public class PaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);

    private final FraudDetectionService service;
    private final ObjectMapper mapper;

    public PaymentConsumer(FraudDetectionService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${fraud.topics.payments}", groupId = "fraud-engine")
    public void onPayment(String message) {
        PaymentEvent event;
        try {
            event = mapper.readValue(message, PaymentEvent.class);
        } catch (Exception ex) {
            log.warn("Skipping malformed payment message: {}", ex.getMessage());
            return;
        }
        try {
            service.process(event);
        } catch (RuntimeException ex) {
            log.error("Failed to score payment {}", event.txId(), ex);
        }
    }
}
