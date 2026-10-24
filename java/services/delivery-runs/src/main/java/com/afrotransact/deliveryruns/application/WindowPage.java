package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.Map;

/**
 * A page of the staff list.
 *
 * @param items rows
 * @param total rows matching
 * @param page  zero-based page
 * @param size  page size
 * @param counts  counts per list tab (all, open, upcoming, decision, closed, draft)
 * @param enabled the pre-order master switch
 * @param defaultMinOrders the minimum order count a new window starts with
 */
public record WindowPage(List<WindowView> items, int total, int page, int size, Map<String, Integer> counts,
                         boolean enabled, int defaultMinOrders) {
}
