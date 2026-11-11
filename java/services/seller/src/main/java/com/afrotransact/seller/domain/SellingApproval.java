package com.afrotransact.seller.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A seller's approval for one scope. While APPROVED it lets every listing in the scope go live. It runs until the
 * earliest document expiry ({@code expiresAt}); {@link #GRACE} later its listings are paused (never deleted) until a
 * renewal is approved.
 *
 * @param reason        why it is NEEDS_ATTENTION, NOT_AVAILABLE or paused (shown to the seller)
 * @param reapplyAfter  NOT_AVAILABLE: the earliest day the seller may apply again (null: not set)
 * @param paused        listings are paused for a reason other than expiry, such as a performance problem
 * @param applicationId the application that produced (or is changing) this approval
 */
public record SellingApproval(UUID id, String orgId, SellingScope scope, String label, UUID ruleId,
                              SellingApprovalStatus status, String reason, LocalDate reapplyAfter, Instant approvedAt,
                              Instant expiresAt, List<SellingDocument> documents, String applicationId, boolean paused,
                              String pauseReason, Instant pausedAt, boolean listingsPaused, Instant warned30At,
                              Instant warned14At, Instant warned7At, Instant expiredNotifiedAt, Instant createdAt,
                              Instant updatedAt, long version) {

    /** After expiry the listings stay up for this long. */
    public static final Duration GRACE = Duration.ofDays(14);
    /** Warnings go out this many days before the earliest document expires. */
    public static final int[] WARN_DAYS = {30, 14, 7};

    public SellingApproval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(status, "status");
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public static SellingApproval applied(String orgId, SellingRule rule, String applicationId, Instant now) {
        return new SellingApproval(UUID.randomUUID(), orgId, rule.scope(), rule.label(), rule.id(),
                SellingApprovalStatus.APPLIED, null, null, null, null, List.of(), applicationId, false, null, null,
                false, null, null, null, null, now, now, 0);
    }

    /** Valid for new listings: approved, not expired, not paused. */
    public boolean valid(Instant now) {
        return status == SellingApprovalStatus.APPROVED && !paused && (expiresAt == null || !now.isAfter(expiresAt));
    }

    public boolean expired(Instant now) {
        return status == SellingApprovalStatus.EXPIRED
                || status == SellingApprovalStatus.APPROVED && expiresAt != null && now.isAfter(expiresAt);
    }

    /** When the grace period ends and the scope's listings are paused (null without an expiry). */
    public Instant graceEndsAt() {
        return expiresAt == null ? null : expiresAt.plus(GRACE);
    }

    /** Whole days from {@code now} to the expiry (negative once expired); null without an expiry. */
    public Long daysToExpiry(Instant now) {
        if (expiresAt == null) {
            return null;
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        return today.until(expiresAt.atZone(ZoneOffset.UTC).toLocalDate(), ChronoUnit.DAYS);
    }

    /** The earliest expiry among the documents (null when none expires). */
    public static Instant earliestExpiry(List<SellingDocument> docs) {
        return docs.stream().map(SellingDocument::expiresOn).filter(Objects::nonNull).min(LocalDate::compareTo)
                .map(d -> d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(1)).orElse(null);
    }

    public SellingApproval approved(SellingApplication app, Instant now) {
        return new SellingApproval(id, orgId, scope, label, ruleId, SellingApprovalStatus.APPROVED, null, null, now,
                earliestExpiry(app.documents()), app.documents(), app.id(), false, null, null, false, null, null,
                null, null, createdAt, now, version);
    }

    public SellingApproval with(SellingApprovalStatus next, String why, LocalDate reapply, String applicationId,
                                Instant now) {
        return new SellingApproval(id, orgId, scope, label, ruleId, next, why, reapply, approvedAt, expiresAt,
                documents, applicationId == null ? this.applicationId : applicationId, paused, pauseReason, pausedAt,
                listingsPaused, warned30At, warned14At, warned7At, expiredNotifiedAt, createdAt, now, version);
    }

    public SellingApproval expiredNow(Instant now) {
        return new SellingApproval(id, orgId, scope, label, ruleId, SellingApprovalStatus.EXPIRED,
                "A document expired", reapplyAfter, approvedAt, expiresAt, documents, applicationId, paused,
                pauseReason, pausedAt, listingsPaused, warned30At, warned14At, warned7At, now, createdAt, now, version);
    }

    public SellingApproval warned(int days, Instant now) {
        return new SellingApproval(id, orgId, scope, label, ruleId, status, reason, reapplyAfter, approvedAt,
                expiresAt, documents, applicationId, paused, pauseReason, pausedAt, listingsPaused,
                days == 30 ? now : warned30At, days == 14 ? now : warned14At, days == 7 ? now : warned7At,
                expiredNotifiedAt, createdAt, now, version);
    }

    /** Listings of the scope were paused (the expiry grace ended, or staff paused them). */
    public SellingApproval listingsPaused(boolean value, Instant now) {
        return new SellingApproval(id, orgId, scope, label, ruleId, status, reason, reapplyAfter, approvedAt,
                expiresAt, documents, applicationId, paused, pauseReason, pausedAt, value, warned30At, warned14At,
                warned7At, expiredNotifiedAt, createdAt, now, version);
    }

    /** Staff pause (performance, a new rule) or lift the pause; the approval itself is kept. */
    public SellingApproval withPause(boolean value, String why, Instant now) {
        return new SellingApproval(id, orgId, scope, label, ruleId, status, reason, reapplyAfter, approvedAt,
                expiresAt, documents, applicationId, value, value ? why : null, value ? now : null, listingsPaused,
                warned30At, warned14At, warned7At, expiredNotifiedAt, createdAt, now, version);
    }
}
