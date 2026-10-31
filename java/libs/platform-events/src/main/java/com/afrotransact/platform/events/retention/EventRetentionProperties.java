package com.afrotransact.platform.events.retention;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention of the platform tables, bound from {@code at.events.retention.*}.
 *
 * <p>A duplicate delivery is harmless only while {@code platform_inbox} still holds the event. Duplicates come from an
 * offset reset (topic retention, 7 days), a dead-letter replay (14 days in the DLQ) and an outbox re-publish, so the
 * inbox is kept at least {@link #MIN_INBOX_RETENTION} (7 + 14 + 14 margin). A configured value below that makes the
 * application refuse to start.
 *
 * @param enabled         set to {@code false} to opt out of the scheduled purge
 * @param cron            when the purge runs (Spring cron, server time zone); default 03:30 daily
 * @param inboxRetention  inbox rows older than this are deleted (default 90 days, minimum 35 days)
 * @param outboxRetention only outbox rows already published and older than this are deleted (default 14 days,
 *                        minimum 7 days); unpublished rows are never deleted
 * @param batchSize       rows deleted per statement (each batch is its own transaction)
 * @param maxBatches      batches per table and run; the next run continues where this one stopped
 */
@ConfigurationProperties(prefix = "at.events.retention")
public record EventRetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 30 3 * * *") String cron,
        @DefaultValue("90d") Duration inboxRetention,
        @DefaultValue("14d") Duration outboxRetention,
        @DefaultValue("5000") int batchSize,
        @DefaultValue("200") int maxBatches) {

    /** Hard floor for the inbox: topic retention 7 + DLQ retention 14 + margin 14 days. */
    public static final Duration MIN_INBOX_RETENTION = Duration.ofDays(35);

    /** Floor for published outbox rows: the topic retention. */
    public static final Duration MIN_OUTBOX_RETENTION = Duration.ofDays(7);

    public EventRetentionProperties {
        if (inboxRetention.compareTo(MIN_INBOX_RETENTION) < 0) {
            throw new IllegalStateException("at.events.retention.inbox-retention=" + inboxRetention
                    + " is below the 35 day minimum (7 day topic + 14 day DLQ + 14 day margin); a replayed or "
                    + "re-delivered event older than the inbox would be processed twice");
        }
        if (outboxRetention.compareTo(MIN_OUTBOX_RETENTION) < 0) {
            throw new IllegalStateException("at.events.retention.outbox-retention=" + outboxRetention
                    + " is below the 7 day minimum");
        }
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalStateException("at.events.retention.batch-size and max-batches must be at least 1");
        }
    }
}
