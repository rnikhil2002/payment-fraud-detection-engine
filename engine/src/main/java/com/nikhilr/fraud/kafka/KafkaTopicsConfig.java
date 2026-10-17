package com.nikhilr.fraud.kafka;

import com.nikhilr.fraud.config.FraudProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopic paymentsTopic(FraudProperties properties) {
        return TopicBuilder.name(properties.topics().payments()).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic alertsTopic(FraudProperties properties) {
        return TopicBuilder.name(properties.topics().alerts()).partitions(3).replicas(1).build();
    }
}
