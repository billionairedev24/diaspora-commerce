package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Metrics, the resulting status and the targets breached, as computed at {@code computedAt}. */
public record SellerHealthReport(SellerHealthMetrics metrics, SellerHealthStatus status, List<String> breaches,
                                 Instant computedAt) {

    public SellerHealthReport {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(computedAt, "computedAt");
        breaches = breaches == null ? List.of() : List.copyOf(breaches);
    }
}
