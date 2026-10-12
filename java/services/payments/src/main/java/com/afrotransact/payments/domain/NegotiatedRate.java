package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A commission or connection fee negotiated with one organisation, for one category or for every category ({@code *}),
 * from {@code startsAt} until {@code endsAt} (open-ended when null). It wins over the plan, category and default rates
 * while active. Staff create it with a required reason and may end it early (with a reason); a job records the expiry
 * at the end date. The row is never deleted, so statements can always say which rate applied and why.
 *
 * @param negotiatedBy who negotiated it (staff), may differ from {@code createdBy} who entered it
 * @param reference    contract or ticket reference, optional
 * @param endedAt      set when staff ended it early
 * @param expiredAt    set by the expiry job once {@code endsAt} passed
 * @param version      optimistic-locking version of the row
 */
public record NegotiatedRate(UUID id, String orgId, NegotiatedRateType type, String category, int rateBps,
                             Instant startsAt, Instant endsAt, String reason, String negotiatedBy, String reference,
                             String createdBy, Instant createdAt, Instant endedAt, String endedBy, String endReason,
                             Instant expiredAt, long version) {

    public static final String ANY = CommissionOverride.ANY;
    public static final int MAX_REASON = 500;

    public NegotiatedRate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(startsAt, "startsAt");
        if (orgId == null || orgId.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "orgId is required", Map.of("orgId", "Required"));
        }
        orgId = orgId.trim();
        category = normaliseCategory(category);
        FeeSchedule.requireBps(rateBps, "negotiated rate");
        if (reason == null || reason.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "A reason is required",
                    Map.of("reason", "Required"));
        }
        reason = reason.trim();
        if (reason.length() > MAX_REASON) {
            throw new PaymentsRuleViolation("payments.invalid-request", "The reason is too long",
                    Map.of("reason", "At most " + MAX_REASON + " characters"));
        }
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new PaymentsRuleViolation("payments.invalid-request", "The end must be after the start",
                    Map.of("endsAt", "Must be after startsAt"));
        }
        reference = reference == null || reference.isBlank() ? null : reference.trim();
        negotiatedBy = negotiatedBy == null || negotiatedBy.isBlank() ? createdBy : negotiatedBy.trim();
    }

    /** "*" for every category, else the lower-cased category. */
    public static String normaliseCategory(String category) {
        return category == null || category.isBlank() ? ANY : category.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A new negotiated rate. It starts now or later ({@code payments.negotiated-rate-backdated} otherwise), because a
     * rate never re-prices payments already created.
     */
    public static NegotiatedRate create(UUID id, String orgId, NegotiatedRateType type, String category, int rateBps,
                                        Instant startsAt, Instant endsAt, String reason, String negotiatedBy,
                                        String reference, String createdBy, Instant now) {
        Instant start = startsAt == null ? now : startsAt;
        if (start.isBefore(now.minusSeconds(60))) {
            throw new PaymentsRuleViolation("payments.negotiated-rate-backdated",
                    "A negotiated rate cannot start in the past (" + start + ")",
                    Map.of("startsAt", "Must be now or later"));
        }
        if (endsAt != null && !endsAt.isAfter(now)) {
            throw new PaymentsRuleViolation("payments.invalid-request", "The end must be in the future",
                    Map.of("endsAt", "Must be in the future"));
        }
        return new NegotiatedRate(id, orgId, type, category, rateBps, start.isBefore(now) ? now : start, endsAt,
                reason, negotiatedBy, reference, createdBy, now, null, null, null, null, 0);
    }

    /** The moment it stops applying: the earlier of its end date and the time staff ended it (null = never). */
    public Instant effectiveEnd() {
        if (endedAt == null) {
            return endsAt;
        }
        return endsAt == null || endedAt.isBefore(endsAt) ? endedAt : endsAt;
    }

    /** In force at {@code at}: started, not past its end date and not ended. */
    public boolean activeAt(Instant at) {
        Instant end = effectiveEnd();
        return !startsAt.isAfter(at) && (end == null || at.isBefore(end));
    }

    public NegotiatedRateStatus status(Instant now) {
        if (endedAt != null && !endedAt.isAfter(now) && (endsAt == null || endedAt.isBefore(endsAt))) {
            return NegotiatedRateStatus.ENDED;
        }
        if (endsAt != null && !endsAt.isAfter(now)) {
            return NegotiatedRateStatus.EXPIRED;
        }
        return startsAt.isAfter(now) ? NegotiatedRateStatus.SCHEDULED : NegotiatedRateStatus.ACTIVE;
    }

    /** Both apply to the same organisation, type and category for some common moment. */
    public boolean overlaps(NegotiatedRate other) {
        if (!orgId.equals(other.orgId) || type != other.type || !category.equals(other.category)) {
            return false;
        }
        Instant end = effectiveEnd();
        Instant otherEnd = other.effectiveEnd();
        boolean startsBeforeOtherEnds = otherEnd == null || startsAt.isBefore(otherEnd);
        boolean otherStartsBeforeEnd = end == null || other.startsAt.isBefore(end);
        return startsBeforeOtherEnds && otherStartsBeforeEnd;
    }

    /** Ends the rate now (staff, with a reason). A rate that has already stopped applying cannot be ended. */
    public NegotiatedRate end(Instant now, String by, String why) {
        if (why == null || why.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "A reason is required",
                    Map.of("reason", "Required"));
        }
        NegotiatedRateStatus status = status(now);
        if (status == NegotiatedRateStatus.ENDED || status == NegotiatedRateStatus.EXPIRED) {
            throw new PaymentsStateConflict("payments.negotiated-rate-ended", "This negotiated rate already ended");
        }
        return new NegotiatedRate(id, orgId, type, category, rateBps, startsAt, endsAt, reason, negotiatedBy,
                reference, createdBy, createdAt, now, by, why.trim(), expiredAt, version);
    }

    /** Records that the end date passed (the expiry job). */
    public NegotiatedRate expire(Instant now) {
        return new NegotiatedRate(id, orgId, type, category, rateBps, startsAt, endsAt, reason, negotiatedBy,
                reference, createdBy, createdAt, endedAt, endedBy, endReason, now, version);
    }

    /** Due for the expiry job: past its end date, not ended early and not recorded yet. */
    public boolean dueForExpiry(Instant now) {
        return expiredAt == null && endedAt == null && endsAt != null && !endsAt.isAfter(now);
    }
}
