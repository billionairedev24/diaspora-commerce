package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Plan fees collected from settlement earnings instead of the card (feature flag, default off). */
public interface EarningsCollectionRepository {

    /** Inserts once per invoice; false when the invoice was already handled. */
    boolean claim(String invoiceId, String orgId, long amountCents, Instant at);

    void update(String invoiceId, String status, String paymentsReference, String balanceTransactionId,
                String reason, Instant at);

    Optional<Row> find(String invoiceId);

    /** REQUESTED / WITHHELD collections to (re)process, oldest first; counts an attempt. */
    List<Row> open(int maxAttempts, int limit);

    List<Row> forOrg(String orgId, int limit);

    /** One invoice's collection. */
    record Row(String invoiceId, String orgId, long amountCents, String status, String paymentsReference,
               String balanceTransactionId, String reason, Instant createdAt) {
    }
}
