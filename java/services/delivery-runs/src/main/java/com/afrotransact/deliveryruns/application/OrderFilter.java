package com.afrotransact.deliveryruns.application;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;

/**
 * Orders tab filter.
 *
 * @param status  PAID, LATE_ADD or CANCELLED (null = all)
 * @param channel attribution channel (null = all)
 * @param q       search in order number, customer, phone, address and zip
 */
public record OrderFilter(PreorderOrderStatus status, AttributionChannel channel, String q) {

    public static final OrderFilter ALL = new OrderFilter(null, null, null);

    public boolean matches(PreorderOrder o) {
        if (status != null && o.status() != status) {
            return false;
        }
        if (channel != null && o.channel() != channel) {
            return false;
        }
        if (q == null || q.isBlank()) {
            return true;
        }
        String haystack = String.join(" ", nz(o.number()), nz(o.orderId()), nz(o.customerName()), nz(o.phone()),
                nz(o.email()), nz(o.address().line()), nz(o.address().city()), nz(o.address().zip()))
                .toLowerCase(Locale.ROOT);
        return haystack.contains(q.trim().toLowerCase(Locale.ROOT));
    }

    /** The filter as audit-log fields. */
    public Map<String, String> describe() {
        Map<String, String> m = new LinkedHashMap<>();
        if (status != null) {
            m.put("status", status.name());
        }
        if (channel != null) {
            m.put("channel", channel.name());
        }
        if (q != null && !q.isBlank()) {
            m.put("q", q.trim());
        }
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
