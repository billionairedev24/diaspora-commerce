package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

/**
 * Who qualifies for a rule now.
 *
 * @param qualifying how many would get it now (before max grants)
 * @param pending    rules checked at window end: how many are in their window, to be checked later
 */
public record RewardPreview(int evaluated, int qualifying, int pending, List<Row> rows) {

    public RewardPreview {
        rows = List.copyOf(rows);
    }

    /**
     * One organisation.
     *
     * @param status  GRANTED, QUALIFIES, PENDING, CHECKED, NOT_YET or INELIGIBLE
     * @param checkAt rules checked at window end: when this organisation's check runs
     */
    public record Row(String orgId, String orgName, String planCode, long value, long threshold, int percent,
                      boolean qualifies, boolean alreadyGranted, String reason, String status, Instant checkAt) {

        public boolean pending() {
            return "PENDING".equals(status);
        }
    }
}
