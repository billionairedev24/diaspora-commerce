package com.afrotransact.seller.application;

import java.util.List;

/**
 * Who qualifies for a rule now.
 *
 * @param qualifying how many would get it now (before max grants)
 */
public record RewardPreview(int evaluated, int qualifying, List<Row> rows) {

    public RewardPreview {
        rows = List.copyOf(rows);
    }

    /** One organisation. */
    public record Row(String orgId, String orgName, String planCode, long value, long threshold, int percent,
                      boolean qualifies, boolean alreadyGranted, String reason) {
    }
}
