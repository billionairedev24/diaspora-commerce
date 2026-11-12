package com.afrotransact.offers.port;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Audit trail of what bulk uploads did to offers (S393, S394): one row per offer created, changed or held. */
public interface OfferAuditRepository {

    void record(Entry entry);

    /**
     * @param action     CREATED, UPDATED or HELD
     * @param source     IMPORT_OFFER or IMPORT_PRICE_QTY
     * @param importId   the upload; null when a caller sent none
     * @param rowKey     the file row (as the uploader numbers it)
     * @param actor      the token's subject (the service that sent the batch)
     * @param onBehalfOf the person who uploaded the file, when the caller said so
     * @param changes    field to {@code {from, to}} for an update; the new terms for a creation
     */
    record Entry(UUID id, UUID offerId, String sellerOrg, String atin, String action, String source, UUID importId,
                 String rowKey, String actor, String onBehalfOf, Map<String, Object> changes, Instant at) {

        public Entry {
            changes = changes == null ? Map.of() : Map.copyOf(changes);
        }
    }
}
