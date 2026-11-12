package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A seller's request to change a product page that is not theirs, to report a wrong match, or to report a duplicate page
 * (S385). The page is never changed by filing; staff decide, and an approval is applied by a separate component that
 * never changes the product's status.
 *
 * @param atin        the ATIN of the page the request is about
 * @param variantAtin the variant the seller was looking at (WRONG_MATCH, NEW_VARIANT), else null
 * @param gtin14      the barcode of a WRONG_MATCH report (the match key), else null
 * @param field       the attribute key (ATTRIBUTE) or image slot; empty otherwise
 * @param payload     what is asked, by type (see the contract)
 * @param reason      the staff reason, shown to the requester
 * @param resolution  how staff resolved a WRONG_MATCH or DUPLICATE_PAGE (CORRECT_GTIN, MOVE_VARIANT, SPLIT_VARIANT, MERGE)
 */
public record ProductChangeRequest(UUID id, UUID productId, String atin, String variantAtin, String gtin14,
                                   ChangeRequestType type, String field, Map<String, Object> payload, String note,
                                   ChangeRequestStatus status, String requestedByOrg, String requestedBy,
                                   Instant requestedAt, Instant slaDueAt, String decidedBy, Instant decidedAt,
                                   String reason, String resolution, long version) {

    /** The shortest reason staff must give for a decision. */
    public static final int MIN_REASON = 3;

    public ProductChangeRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(atin, "atin");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedByOrg, "requestedByOrg");
        field = field == null ? "" : field;
        payload = payload == null ? Map.of() : payload;
    }

    /** A new request, waiting. */
    public static ProductChangeRequest filed(UUID productId, String atin, String variantAtin, String gtin14,
                                             ChangeRequestType type, String field, Map<String, Object> payload,
                                             String note, String requestedByOrg, String requestedBy, Instant now,
                                             Instant slaDueAt) {
        return new ProductChangeRequest(UUID.randomUUID(), productId, atin, variantAtin, gtin14, type, field, payload,
                note, ChangeRequestStatus.PENDING, requestedByOrg, requestedBy, now, slaDueAt, null, null, null, null,
                0);
    }

    public boolean open() {
        return status.open();
    }

    /** Staff applied it. */
    public ProductChangeRequest applied(String by, String why, String how, Instant now) {
        return decided(ChangeRequestStatus.APPLIED, by, why, how, now);
    }

    /** Staff did not apply it, and say why. */
    public ProductChangeRequest rejected(String by, String why, Instant now) {
        return decided(ChangeRequestStatus.REJECTED, by, why, null, now);
    }

    /** The requester took it back. */
    public ProductChangeRequest withdrawn(String by, Instant now) {
        if (!open()) {
            throw new IllegalStateException("Only a waiting request can be withdrawn (was " + status + ")");
        }
        return new ProductChangeRequest(id, productId, atin, variantAtin, gtin14, type, field, payload, note,
                ChangeRequestStatus.WITHDRAWN, requestedByOrg, requestedBy, requestedAt, slaDueAt, by, now, null, null,
                version);
    }

    private ProductChangeRequest decided(ChangeRequestStatus next, String by, String why, String how, Instant now) {
        if (!open()) {
            throw new IllegalStateException("The request was already decided (" + status + ")");
        }
        if (why == null || why.strip().length() < MIN_REASON) {
            throw new IllegalArgumentException("Give a reason of at least " + MIN_REASON + " characters");
        }
        return new ProductChangeRequest(id, productId, atin, variantAtin, gtin14, type, field, payload, note, next,
                requestedByOrg, requestedBy, requestedAt, slaDueAt, by, now, why.strip(), how, version);
    }
}
