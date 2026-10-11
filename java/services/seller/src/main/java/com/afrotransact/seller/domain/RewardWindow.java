package com.afrotransact.seller.domain;

import java.time.LocalDate;

/**
 * The days a rule's metric is read over (inclusive), in UTC.
 *
 * @param open false once a "within N days of approval" window has passed: gauges can no longer qualify and sums are
 *             final
 */
public record RewardWindow(LocalDate from, LocalDate to, boolean open) {

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
}
