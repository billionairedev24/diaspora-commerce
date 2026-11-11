package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.List;

import com.afrotransact.orders.domain.QuoteSettings;

/** The delivery quote settings row and its audit trail. */
public interface DeliveryQuoteSettingsRepository {

    QuoteSettings load();

    /** Reads the row under a write lock (round-robin cursor, concurrent edits). */
    QuoteSettings lock();

    /** Stores the settings and the audit row in the caller's transaction. */
    void save(QuoteSettings next, String actorId, String actorName, String reason, QuoteSettings before, Instant at);

    /** Stores only the round-robin cursor (not an edit, not audited). */
    void saveCursor(String cursor);

    List<Change> changes(int limit);

    record Change(String actorName, String reason, Instant changedAt) {
    }
}
