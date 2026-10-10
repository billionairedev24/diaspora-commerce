package com.afrotransact.mcp.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Kafka and Valkey shared by the Spring ITs (local images only). */
final class ItContainers {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse("valkey/valkey:8.1-alpine")).withExposedPorts(6379);

    static {
        KAFKA.start();
        VALKEY.start();
    }

    private ItContainers() {
    }

    static String valkeyUrl() {
        return "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379);
    }
}
