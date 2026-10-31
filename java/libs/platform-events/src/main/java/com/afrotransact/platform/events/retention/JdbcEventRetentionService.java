package com.afrotransact.platform.events.retention;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Purges in small batches: each batch is one autocommit statement deleting at most {@code batch-size} rows, so no
 * long transaction or table lock builds up, and at most {@code max-batches} batches run per table. Neither table has
 * an index on the age column, so every batch scans; that is why the batch is small and the run is off-peak.
 */
class JdbcEventRetentionService implements EventRetentionService {

    static final String INBOX_PURGED = "events.inbox.purged";
    static final String OUTBOX_PURGED = "events.outbox.purged";
    static final String INBOX_OLDEST_AGE = "events.inbox.oldest.age";

    private static final Logger LOG = LoggerFactory.getLogger(JdbcEventRetentionService.class);
    private static final long UNKNOWN = -1;

    private static final String DELETE_INBOX = """
            delete from platform_inbox where ctid = any(array(
                select ctid from platform_inbox where processed_at < :cutoff limit :batch))
            """;
    private static final String DELETE_OUTBOX = """
            delete from platform_outbox where ctid = any(array(
                select ctid from platform_outbox
                 where published_at is not null and published_at < :cutoff limit :batch))
            """;

    private final JdbcClient jdbc;
    private final RetentionRunLock lock;
    private final EventRetentionProperties properties;
    private final Clock clock;
    private final Counter inboxPurged;
    private final Counter outboxPurged;
    private final AtomicLong oldestInboxAgeSeconds = new AtomicLong(UNKNOWN);

    JdbcEventRetentionService(JdbcClient jdbc, RetentionRunLock lock, EventRetentionProperties properties,
                              Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.properties = properties;
        this.clock = clock;
        this.inboxPurged = Counter.builder(INBOX_PURGED).description("platform_inbox rows deleted by retention")
                .register(meters);
        this.outboxPurged = Counter.builder(OUTBOX_PURGED)
                .description("published platform_outbox rows deleted by retention").register(meters);
        Gauge.builder(INBOX_OLDEST_AGE, oldestInboxAgeSeconds, AtomicLong::doubleValue).baseUnit("seconds")
                .description("Age of the oldest platform_inbox row after the last purge run (-1 = unknown or empty)")
                .register(meters);
    }

    @Override
    public Optional<PurgeResult> purge() {
        if (!tablesExist()) {
            LOG.debug("platform_inbox / platform_outbox not present in this database; retention skipped");
            return Optional.empty();
        }
        Optional<PurgeResult> result = lock.tryRun(this::purgeLocked);
        if (result.isEmpty()) {
            LOG.info("Event retention skipped: another instance is purging");
        }
        return result;
    }

    private PurgeResult purgeLocked() {
        Instant now = clock.instant();
        Batches inbox = deleteInBatches(DELETE_INBOX, Timestamp.from(now.minus(properties.inboxRetention())));
        Batches outbox = deleteInBatches(DELETE_OUTBOX, Timestamp.from(now.minus(properties.outboxRetention())));
        inboxPurged.increment(inbox.deleted());
        outboxPurged.increment(outbox.deleted());
        refreshOldestInboxAge(now);
        boolean complete = !inbox.stoppedEarly() && !outbox.stoppedEarly();
        LOG.info("Event retention: inbox deleted={} outbox deleted={} complete={}", inbox.deleted(),
                outbox.deleted(), complete);
        return new PurgeResult(inbox.deleted(), outbox.deleted(), complete);
    }

    private Batches deleteInBatches(String sql, Timestamp cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            int n = jdbc.sql(sql).param("cutoff", cutoff).param("batch", properties.batchSize()).update();
            deleted += n;
            if (n < properties.batchSize()) {
                return new Batches(deleted, false);
            }
        }
        return new Batches(deleted, true);
    }

    private void refreshOldestInboxAge(Instant now) {
        Optional<Timestamp> oldest = jdbc.sql("select min(processed_at) from platform_inbox")
                .query(Timestamp.class).optional();
        oldestInboxAgeSeconds.set(oldest.map(t -> Duration.between(t.toInstant(), now).toSeconds()).orElse(UNKNOWN));
    }

    private boolean tablesExist() {
        return Boolean.TRUE.equals(jdbc.sql(
                "select to_regclass('platform_inbox') is not null and to_regclass('platform_outbox') is not null")
                .query(Boolean.class).single());
    }

    /** Rows deleted from one table, and whether max-batches stopped the loop while full batches were still due. */
    private record Batches(long deleted, boolean stoppedEarly) {
    }
}
