package com.afrotransact.catalog.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's review of a product they received (S324): 1-5 stars, an optional title and text, up to
 * {@value #MAX_PHOTOS} photos. One live review per customer and product. New reviews pass the automatic checks and
 * are {@link ReviewStatus#PUBLISHED} straight away or wait as {@link ReviewStatus#PENDING} for trust-ops'
 * moderation queue; moderation can reject or remove them with a reason, the customer can edit within the edit window
 * and delete, and the product's owner replies once to a published review.
 *
 * @param atin         the product ATIN (the review belongs to the product page, not to a seller's offer)
 * @param productTitle title as reviewed (for the moderation queue and the seller's list)
 * @param sellerOrgId  the product owner, who may reply and report
 * @param reviewerName public display name: first name and last initial ("Adaeze N.")
 * @param orderId      the delivering order (evidence, never public)
 * @param flags        what the automatic checks found in the last version of the text
 * @param moderation   the last moderation decision, null for reviews that were published automatically
 * @param reply        the owner's reply, null until they reply
 * @param reportCount  reports on the current version of the text
 * @param version      optimistic lock
 */
public record ProductReview(UUID id, UUID productId, String atin, String productTitle, String sellerOrgId,
                            String customerId, String reviewerName, String orderId, String orderNumber,
                            ReviewDraft content, ReviewStatus status, List<ReviewFlag> flags, Instant createdAt,
                            Instant updatedAt, Moderation moderation, SellerReply reply, int reportCount,
                            long version) {

    public static final int MAX_TITLE = 120;
    public static final int MAX_TEXT = 4000;
    public static final int MAX_PHOTOS = 5;
    public static final int MAX_REPLY = 1000;
    public static final int MAX_NOTE = 500;
    /** Who "moderated" a review that passed the automatic checks. */
    public static final String AUTO_CHECKS = "system:auto-checks";

    /**
     * A moderation decision.
     *
     * @param by     staff subject or service client (trust-ops) that decided
     * @param reason why it was refused (null when approved)
     * @param note   optional note (required for {@link ReviewReasonCode#OTHER})
     */
    public record Moderation(String by, Instant at, ReviewReasonCode reason, String note) {

        public Moderation {
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * The owner's single public reply.
     *
     * @param by subject of the seller member who replied
     */
    public record SellerReply(String text, String by, Instant at) {

        public SellerReply {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(at, "at");
        }
    }

    public ProductReview {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(atin, "atin");
        Objects.requireNonNull(sellerOrgId, "sellerOrgId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    /**
     * A customer who received the product writes a review. Published at once when the automatic checks find nothing,
     * otherwise pending moderation.
     *
     * @param fullName the shopper's account name; only first name and last initial are kept
     */
    public static ProductReview write(UUID id, Product product, String customerId, String fullName,
                                      PurchaseProof purchase, ReviewDraft draft, ReviewContentPolicy policy,
                                      Instant now) {
        validate(draft);
        List<ReviewFlag> flags = policy.screen(draft.title(), draft.text());
        boolean clean = flags.isEmpty();
        return new ProductReview(id, product.id(), product.atin().value(), product.title(), product.owner().orgId(),
                customerId, displayName(fullName), purchase.orderId(), purchase.orderNumber(), draft,
                clean ? ReviewStatus.PUBLISHED : ReviewStatus.PENDING, flags, now, now,
                clean ? new Moderation(AUTO_CHECKS, now, null, null) : null, null, 0, 0);
    }

    /** Public and counted in ratings. */
    public boolean published() {
        return status.published();
    }

    public int rating() {
        return content.rating();
    }

    /** The review can be edited until this moment. */
    public Instant editableUntil(Duration window) {
        return createdAt.plus(window);
    }

    public boolean editable(Duration window, Instant now) {
        return status != ReviewStatus.DELETED && now.isBefore(editableUntil(window));
    }

    /**
     * The customer edits their review within the window. The checks run again: a clean edit of a published or pending
     * review is published; a flagged one goes (back) to moderation; an edit of a rejected or removed review always
     * goes to moderation, so a staff decision cannot be undone by rewording alone. Reports on the old text are
     * dismissed.
     */
    public ProductReview edit(ReviewDraft draft, ReviewContentPolicy policy, Duration window, Instant now) {
        if (status == ReviewStatus.DELETED) {
            throw new ReviewStateConflict("catalog.review-deleted", "This review was deleted");
        }
        if (!editable(window, now)) {
            throw new ReviewStateConflict("catalog.review-edit-window-closed", "Reviews can be edited for "
                    + window.toDays() + " days after they were written");
        }
        validate(draft);
        List<ReviewFlag> found = policy.screen(draft.title(), draft.text());
        ReviewStatus next = !status.refused() && found.isEmpty() ? ReviewStatus.PUBLISHED : ReviewStatus.PENDING;
        Moderation m = next == ReviewStatus.PUBLISHED ? new Moderation(AUTO_CHECKS, now, null, null) : null;
        return new ProductReview(id, productId, atin, productTitle, sellerOrgId, customerId, reviewerName, orderId,
                orderNumber, draft, next, found, createdAt, now, m, reply, 0, version);
    }

    /** The customer deletes their review (any state, any time). Deleting a deleted review is a no-op. */
    public ProductReview delete(Instant now) {
        if (status == ReviewStatus.DELETED) {
            return this;
        }
        return with(ReviewStatus.DELETED, moderation, reply, reportCount, now);
    }

    /**
     * Moderation publishes the review. A published review stays as it is (a retried decision is safe); a rejected or
     * removed review can be reinstated.
     */
    public ProductReview approve(String by, String note, Instant now) {
        if (status == ReviewStatus.DELETED) {
            throw new ReviewStateConflict("catalog.review-deleted", "This review was deleted");
        }
        if (status == ReviewStatus.PUBLISHED) {
            return this;
        }
        return with(ReviewStatus.PUBLISHED, new Moderation(by, now, null, note(note, false)), reply, 0, now);
    }

    /**
     * Moderation refuses a review that is waiting for a decision. Rejecting a rejected or removed review changes
     * nothing; a published review is taken down with {@link #remove} instead.
     */
    public ProductReview reject(String by, ReviewReasonCode reason, String note, Instant now) {
        if (status == ReviewStatus.REJECTED || status == ReviewStatus.REMOVED) {
            return this;
        }
        if (status != ReviewStatus.PENDING) {
            throw new ReviewStateConflict("catalog.review-not-pending", "Only reviews waiting for moderation can be "
                    + "rejected; remove a published review instead");
        }
        return with(ReviewStatus.REJECTED, decision(by, reason, note, now), reply, reportCount, now);
    }

    /** Moderation takes a review down, published or not. Removing a removed or rejected review changes nothing. */
    public ProductReview remove(String by, ReviewReasonCode reason, String note, Instant now) {
        if (status == ReviewStatus.REMOVED || status == ReviewStatus.REJECTED) {
            return this;
        }
        if (status == ReviewStatus.DELETED) {
            throw new ReviewStateConflict("catalog.review-deleted", "This review was deleted");
        }
        return with(ReviewStatus.REMOVED, decision(by, reason, note, now), reply, reportCount, now);
    }

    /** The product's owner replies once, to a published review; they can neither edit nor hide a review. */
    public ProductReview replyBy(String by, String replyText, Instant now) {
        if (status != ReviewStatus.PUBLISHED) {
            throw new ReviewStateConflict("catalog.review-not-published", "You can reply once the review is published");
        }
        if (reply != null) {
            throw new ReviewStateConflict("catalog.review-already-replied", "You have already replied to this review");
        }
        String t = replyText == null ? "" : replyText.strip();
        if (t.isEmpty() || t.length() > MAX_REPLY) {
            throw new CatalogRuleViolation("Replies are 1 to " + MAX_REPLY + " characters",
                    Map.of("text", "1 to " + MAX_REPLY + " characters"));
        }
        return with(status, moderation, new SellerReply(t, by, now), reportCount, updatedAt);
    }

    /** This review with the report counter set (only published reviews are reported). */
    public ProductReview withReportCount(int reports) {
        return with(status, moderation, reply, reports, updatedAt);
    }

    /** "Adaeze Nwosu" becomes "Adaeze N."; a single name stays as it is; no name becomes "Verified customer". */
    public static String displayName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Verified customer";
        }
        String[] parts = fullName.strip().split("\\s+");
        String first = parts[0].length() > 40 ? parts[0].substring(0, 40) : parts[0];
        if (parts.length == 1) {
            return first;
        }
        String last = parts[parts.length - 1];
        return first + " " + last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT) + ".";
    }

    /** Throws {@link CatalogRuleViolation} with per-field messages when the draft breaks a rule. */
    public static void validate(ReviewDraft d) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (d.rating() < 1 || d.rating() > 5) {
            errors.put("rating", "Choose 1 to 5 stars");
        }
        if (d.title() != null && d.title().length() > MAX_TITLE) {
            errors.put("title", "At most " + MAX_TITLE + " characters");
        }
        if (d.text() != null && d.text().length() > MAX_TEXT) {
            errors.put("text", "At most " + MAX_TEXT + " characters");
        }
        if (d.photos().size() > MAX_PHOTOS) {
            errors.put("photos", "Up to " + MAX_PHOTOS + " photos");
        } else if (d.photos().stream().anyMatch(p -> !p.valid())) {
            errors.put("photos", "Photos must be https image links");
        }
        if (!errors.isEmpty()) {
            throw new CatalogRuleViolation("Check your review", errors);
        }
    }

    private Moderation decision(String by, ReviewReasonCode reason, String note, Instant now) {
        if (reason == null) {
            throw new CatalogRuleViolation("Choose why the review is removed", Map.of("reasonCode", "Required"));
        }
        return new Moderation(by, now, reason, note(note, reason == ReviewReasonCode.OTHER));
    }

    private ProductReview with(ReviewStatus next, Moderation m, SellerReply r, int reports, Instant at) {
        return new ProductReview(id, productId, atin, productTitle, sellerOrgId, customerId, reviewerName, orderId,
                orderNumber, content, next, flags, createdAt, at, m, r, reports, version);
    }

    private static String note(String note, boolean required) {
        String n = note == null || note.isBlank() ? null : note.strip();
        if (required && n == null) {
            throw new CatalogRuleViolation("Explain why in the note", Map.of("note", "Required for OTHER"));
        }
        if (n != null && n.length() > MAX_NOTE) {
            throw new CatalogRuleViolation("Notes are at most " + MAX_NOTE + " characters",
                    Map.of("note", "At most " + MAX_NOTE + " characters"));
        }
        return n;
    }
}
