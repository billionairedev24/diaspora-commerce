package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.AreaKey;

/**
 * Pre-order settings of the service ({@code at.delivery-runs.preorders.*}).
 *
 * @param pagePath        storefront path of the pre-order pages ({@code /pre-orders})
 * @param metroCities     next-day metro cities served by geo zones: pre-order is never shown there
 * @param retentionDays   projected orders (PII) are deleted this many days after delivery
 * @param seedDemoAreas   seed the design's demo coverage areas at start-up (local profile only)
 * @param supportPhone    "Missed it? Call …" number in events
 * @param defaultMinOrders minimum order count a new window starts with (0 = none)
 */
public record PreorderOptions(String pagePath, List<AreaKey> metroCities, int retentionDays, boolean seedDemoAreas,
                              String supportPhone, int defaultMinOrders) {

    public PreorderOptions {
        pagePath = pagePath == null || pagePath.isBlank() ? "/pre-orders"
                : pagePath.startsWith("/") ? pagePath : "/" + pagePath;
        metroCities = metroCities == null ? List.of() : List.copyOf(metroCities);
        retentionDays = retentionDays < 1 ? 90 : retentionDays;
        defaultMinOrders = Math.max(0, defaultMinOrders);
    }

    public boolean metro(String country, String region, String city) {
        return city != null && metroCities.stream().anyMatch(m -> m.isCity() && m.covers(country, region, city));
    }
}
