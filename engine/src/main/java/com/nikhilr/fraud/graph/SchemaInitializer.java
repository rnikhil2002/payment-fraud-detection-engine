package com.nikhilr.fraud.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates Neo4j constraints at startup, retrying while the database is still booting. */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final int MAX_ATTEMPTS = 30;

    private final GraphRepository graph;

    public SchemaInitializer(GraphRepository graph) {
        this.graph = graph;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                graph.ensureSchema();
                return;
            } catch (RuntimeException ex) {
                log.warn("Neo4j not ready yet (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, ex.getMessage());
                Thread.sleep(2000);
            }
        }
        throw new IllegalStateException("Could not reach Neo4j to create the schema");
    }
}
