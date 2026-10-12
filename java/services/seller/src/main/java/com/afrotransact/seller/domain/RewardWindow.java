package com.afrotransact.seller.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The days a rule's metric is read over (inclusive), in UTC.
 *
 * @param open     false once a "within N days" window has passed (gauges can no longer qualify and sums are final);
 *                 for a rule checked at window end, false from its check time
 * @param closesAt rules checked at window end: when the window closes (anchor + N days), else null
 * @param checkAt  rules checked at window end: when the check is due (the lead before the window closes, or before
 *                 the renewal when Stripe would invoice first), else null
 * @param dueUntil rules checked at window end: a check not run by then is missed (nothing is granted), else null
 */
public record RewardWindow(LocalDate from, LocalDate to, boolean open, Instant closesAt, Instant checkAt,
                           Instant dueUntil) {

    public RewardWindow(LocalDate from, LocalDate to, boolean open) {
        this(from, to, open, null, null, null);
    }

    /**
     * Lifetime since approval by default; the last {@code windowDays} days; and/or the first
     * {@code withinDaysOfApproval} days after approval (the intersection when both are set).
     */
    public static RewardWindow of(Integer windowDays, Integer withinDaysOfApproval, LocalDate approvedOn,
                                  LocalDate today) {
        LocalDate from = approvedOn == null ? LocalDate.of(2000, 1, 1) : approvedOn;
        LocalDate to = today;
        boolean open = true;
        if (withinDaysOfApproval != null && approvedOn != null) {
            LocalDate last = approvedOn.plusDays(withinDaysOfApproval - 1L);
            if (today.isAfter(last)) {
                to = last;
                open = false;
            }
        }
        if (windowDays != null) {
            LocalDate start = to.minusDays(windowDays - 1L);
            if (start.isAfter(from)) {
                from = start;
            }
        }
        return new RewardWindow(from, to, open);
    }

    /**
     * The window of a rule decided once, when it closes: from {@code anchorAt} for {@code days} days. The check is
     * due {@code lead} before the window closes; when Stripe renews (creates an invoice) at or after that time but
     * before the window closes, {@code lead} before the renewal instead, so a free period is applied before the
     * invoice exists. The metric is read from the anchor's day to the check's day: what is recorded after the check
     * (at most the lead's worth of the window) does not count. A check not run within {@code lead} after the window
     * closed is missed.
     */
    public static RewardWindow closing(Instant anchorAt, int days, Instant renewalAt, Duration lead, Instant now) {
        Instant closesAt = anchorAt.plus(Duration.ofDays(days));
        Instant checkAt = closesAt.minus(lead);
        if (renewalAt != null && !renewalAt.isBefore(checkAt) && renewalAt.isBefore(closesAt)) {
            checkAt = renewalAt.minus(lead);
        }
        if (checkAt.isBefore(anchorAt)) {
            checkAt = anchorAt;
        }
        LocalDate from = LocalDate.ofInstant(anchorAt, ZoneOffset.UTC);
        LocalDate checkDay = LocalDate.ofInstant(checkAt, ZoneOffset.UTC);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate to = today.isBefore(checkDay) ? today : checkDay;
        return new RewardWindow(from, to, now.isBefore(checkAt), closesAt, checkAt, closesAt.plus(lead));
    }

    /** A window-end check is due now (and not missed). */
    public boolean due(Instant now) {
        return checkAt != null && !now.isBefore(checkAt) && now.isBefore(dueUntil);
    }
}
