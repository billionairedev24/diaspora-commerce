package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The outbox relay publishes run events to {@code at.delivery-runs.events}, keyed by run id, with event headers. */
@SpringBootTest(properties = {"at.events.relay-enabled=true", "at.events.relay-interval=200ms",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class OutboxRelayIT {

    /*
     * Both containers are started once and left to Ryuk instead of being stopped after the class: the cached context
     * keeps relaying the outbox every 200 ms until the JVM exits, and must still reach both when it shuts down.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private final LocationService locations;
    private final RunService runs;
    private final MutableClock clock;

    @Autowired
    OutboxRelayIT(LocationService locations, RunService runs, MutableClock clock) {
        this.locations = locations;
        this.runs = runs;
        this.clock = clock;
    }

    @Test
    void runEventsReachTheTopic() {
        clock.set(Instant.parse("2026-10-19T15:00:00Z"));
        Location bastrop = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND));
        Run run = runs.create(Fixtures.run(bastrop.id(), clock.instant(), Duration.ofDays(4)));
        runs.announce(run.id());
        clock.advance(Duration.ofMinutes(1));
        runs.open(run.id());

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("at.delivery-runs.events"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline
                    && seen.stream().filter(r -> r.key().equals(run.id().toString())).count() < 2) {
                consumer.poll(Duration.ofMillis(500)).forEach(seen::add);
            }
        }
        List<ConsumerRecord<String, String>> mine = seen.stream()
                .filter(r -> r.key().equals(run.id().toString())).toList();
        assertThat(mine).extracting(r -> header(r, "event-type"))
                .containsExactly("delivery-runs.run.announced", "delivery-runs.run.opened");
        assertThat(mine).allSatisfy(r -> assertThat(header(r, "event-id")).isNotBlank());
        assertThat(mine.getLast().value()).contains("\"status\": \"OPEN\"", "\"town\": \"Bastrop\"",
                "\"runUrl\": \"http://storefront.test/delivery-runs/");
        assertThat(mine.getFirst().topic()).isEqualTo("at.delivery-runs.events");
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
