package com.afrotransact.platform.events.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs the retention job against a real PostgreSQL with the real table definition
 * ({@code db/platform/V0_0_1__outbox_inbox.sql}), each test in its own schema. Needs
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://host:port/db} (and optionally {@code AT_IT_POSTGRES_USER},
 * {@code AT_IT_POSTGRES_PASSWORD}); skipped when it is not set.
 */
@EnabledIfEnvironmentVariable(named = "AT_IT_POSTGRES_URL", matches = ".+")
class EventRetentionPostgresIT {

    private static final String URL = System.getenv("AT_IT_POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "");

    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcClient jdbc;
    private MeterRegistry meters;

    @BeforeEach
    void createSchemaWithThePlatformTables() throws Exception {
        schema = "retention_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute("create schema " + schema);
        }
        Properties props = new Properties();
        props.setProperty("currentSchema", schema);
        dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
        dataSource.setConnectionProperties(props);
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql(platformSql()).update();
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void dropSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute("drop schema " + schema + " cascade");
        }
    }

    private static String platformSql() throws IOException {
        try (InputStream in = EventRetentionPostgresIT.class.getResourceAsStream(
                "/db/platform/V0_0_1__outbox_inbox.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private EventRetentionService service(int batchSize, int maxBatches) {
        EventRetentionProperties props = new EventRetentionProperties(true, "0 30 3 * * *",
                Duration.ofDays(90), Duration.ofDays(14), batchSize, maxBatches);
        return new JdbcEventRetentionService(jdbc, new PostgresAdvisoryRetentionLock(dataSource), props,
                Clock.systemUTC(), meters);
    }

    private void inbox(String consumer, int daysAgo) {
        jdbc.sql("insert into platform_inbox (consumer, event_id, processed_at) values (:c, :id, "
                        + "now() - make_interval(days => :d))")
                .param("c", consumer).param("id", UUID.randomUUID()).param("d", daysAgo).update();
    }

    private void outbox(int occurredDaysAgo, Integer publishedDaysAgo) {
        jdbc.sql("insert into platform_outbox (id, type, aggregate_type, aggregate_id, occurred_at, payload, "
                        + "published_at) values (:id, 't', 'a', '1', now() - make_interval(days => :o), '{}'::jsonb, "
                        + "case when :p::int is null then null else now() - make_interval(days => :p::int) end)")
                .param("id", UUID.randomUUID()).param("o", occurredDaysAgo)
                .param("p", publishedDaysAgo, Types.INTEGER).update();
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    @Test
    void deletesInboxRowsOlderThanTheRetentionAndKeepsRecentOnes() {
        inbox("c", 120);
        inbox("c", 91);
        inbox("c", 89);
        inbox("c", 40);
        inbox("c", 0);

        PurgeResult result = service(100, 10).purge().orElseThrow();

        assertThat(result.inboxDeleted()).isEqualTo(2);
        assertThat(result.complete()).isTrue();
        assertThat(count("platform_inbox")).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from platform_inbox where processed_at < now() - interval '90 days'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void neverDeletesUnpublishedOutboxRowsOnlyPublishedOnesOlderThanTheRetention() {
        outbox(60, null);       // old but never published: must stay
        outbox(30, 20);         // published 20 days ago: due
        outbox(30, 15);         // published 15 days ago: due
        outbox(30, 5);          // published 5 days ago: stays
        outbox(0, null);

        PurgeResult result = service(100, 10).purge().orElseThrow();

        assertThat(result.outboxDeleted()).isEqualTo(2);
        assertThat(count("platform_outbox")).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from platform_outbox where published_at is null").query(Long.class)
                .single()).isEqualTo(2);
    }

    @Test
    void batchesAreBoundedAndTheNextRunContinueWhereTheLastStopped() {
        for (int i = 0; i < 25; i++) {
            inbox("c", 200);
        }
        inbox("c", 1);

        PurgeResult first = service(10, 2).purge().orElseThrow();
        assertThat(first.inboxDeleted()).isEqualTo(20);
        assertThat(first.complete()).isFalse();
        assertThat(count("platform_inbox")).isEqualTo(6);

        PurgeResult second = service(10, 2).purge().orElseThrow();
        assertThat(second.inboxDeleted()).isEqualTo(5);
        assertThat(second.complete()).isTrue();

        PurgeResult third = service(10, 2).purge().orElseThrow();
        assertThat(third.inboxDeleted()).isZero();
        assertThat(third.outboxDeleted()).isZero();
        assertThat(count("platform_inbox")).isEqualTo(1);
    }

    @Test
    void anExactMultipleOfTheBatchSizeEndsWithAnEmptyBatchAndIsComplete() {
        for (int i = 0; i < 20; i++) {
            inbox("c", 200);
        }

        PurgeResult result = service(10, 5).purge().orElseThrow();

        assertThat(result.inboxDeleted()).isEqualTo(20);
        assertThat(result.complete()).isTrue();
    }

    @Test
    void aRunIsSkippedWhileAnotherSessionHoldsTheAdvisoryLock() throws SQLException {
        inbox("c", 200);
        EventRetentionService retention = service(10, 5);

        try (Connection other = DriverManager.getConnection(URL, USER, PASSWORD)) {
            try (PreparedStatement st = other.prepareStatement("select pg_advisory_lock(?)")) {
                st.setLong(1, PostgresAdvisoryRetentionLock.LOCK_ID);
                st.execute();
            }
            Optional<PurgeResult> skipped = retention.purge();
            assertThat(skipped).isEmpty();
            assertThat(count("platform_inbox")).isEqualTo(1);
            try (PreparedStatement st = other.prepareStatement("select pg_advisory_unlock(?)")) {
                st.setLong(1, PostgresAdvisoryRetentionLock.LOCK_ID);
                st.execute();
            }
        }

        assertThat(retention.purge().orElseThrow().inboxDeleted()).isEqualTo(1);
        // the lock is released after a run: a second run is not blocked by the first
        assertThat(retention.purge()).isPresent();
    }

    @Test
    void metricsCountDeletedRowsAndExposeTheOldestInboxAge() {
        inbox("c", 200);
        inbox("c", 100);
        inbox("c", 50);
        outbox(40, 30);

        service(100, 10).purge().orElseThrow();

        assertThat(meters.get("events.inbox.purged").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("events.outbox.purged").counter().count()).isEqualTo(1.0);
        double oldestSeconds = meters.get("events.inbox.oldest.age").gauge().value();
        assertThat(oldestSeconds).isBetween(49.0 * 86_400, 51.0 * 86_400);
    }

    @Test
    void theOldestAgeIsMinusOneWhenTheInboxIsEmpty() {
        service(100, 10).purge().orElseThrow();

        assertThat(meters.get("events.inbox.oldest.age").gauge().value()).isEqualTo(-1.0);
    }

    @Test
    void aDatabaseWithoutThePlatformTablesIsSkipped() {
        jdbc.sql("drop table platform_inbox").update();
        jdbc.sql("drop table platform_outbox").update();

        assertThat(service(10, 10).purge()).isEmpty();
    }
}
