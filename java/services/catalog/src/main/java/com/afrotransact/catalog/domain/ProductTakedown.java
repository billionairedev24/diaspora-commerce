package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A Trust and Safety takedown of a live listing. While active the product is TAKEN_DOWN; restoring returns it to
 * {@code previousStatus}.
 */
public record ProductTakedown(UUID id, UUID productId, ListingReasonCode reasonCode, String note, String actorId,
                              ProductStatus previousStatus, Instant takenDownAt, Instant restoredAt, String restoredBy,
                              String restoreNote, long version) {

    public static final int NOTE_MAX = 1000;

    public ProductTakedown {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(takenDownAt, "takenDownAt");
    }

    public static ProductTakedown open(UUID id, Product product, ListingReasonCode reasonCode, String note,
                                       String actorId, Instant now) {
        if (reasonCode == null) {
            throw new CatalogRuleViolation("Pick a reason code to take a listing down",
                    Map.of("reasonCode", "Required"));
        }
        String text = clean(note);
        if (reasonCode == ListingReasonCode.OTHER && text == null) {
            throw new CatalogRuleViolation("Explain the takedown in the note", Map.of("note", "Required for OTHER"));
        }
        return new ProductTakedown(id, product.id(), reasonCode, text, actorId, product.status(), now, null, null,
                null, 0);
    }

    public boolean active() {
        return restoredAt == null;
    }

    public ProductTakedown restore(String by, String note, Instant now) {
        if (!active()) {
            throw new IllegalStateException("This takedown was already reversed");
        }
        return new ProductTakedown(id, productId, reasonCode, this.note, actorId, previousStatus, takenDownAt, now, by,
                clean(note), version);
    }

    /** The moderation note stored on the product and shown to the seller: {@code [CODE] note}. */
    public String productNote() {
        return "[" + reasonCode.name() + "] Taken down" + (note == null ? "" : ": " + note);
    }

    private static String clean(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String t = note.trim();
        if (t.length() > NOTE_MAX) {
            throw new CatalogRuleViolation("Note is too long", Map.of("note", "At most " + NOTE_MAX + " characters"));
        }
        return t;
    }
}
