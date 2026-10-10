package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Marketplace targets (defaults follow common marketplace practice): late shipment rate under 4 %, seller
 * cancellation rate under 2.5 %, order defect rate under 1 %. Any breach makes the seller AT_RISK.
 */
public final class DefaultSellerHealthPolicy implements SellerHealthPolicy {

    private final double lateShipmentTarget;
    private final double cancellationTarget;
    private final double defectTarget;

    public DefaultSellerHealthPolicy(double lateShipmentTarget, double cancellationTarget, double defectTarget) {
        this.lateShipmentTarget = lateShipmentTarget;
        this.cancellationTarget = cancellationTarget;
        this.defectTarget = defectTarget;
    }

    public static DefaultSellerHealthPolicy defaults() {
        return new DefaultSellerHealthPolicy(0.04, 0.025, 0.01);
    }

    @Override
    public SellerHealthReport assess(SellerHealthMetrics m, Instant now) {
        if (m.shipments() == 0) {
            return new SellerHealthReport(m, SellerHealthStatus.UNKNOWN, List.of(), now);
        }
        List<String> breaches = new ArrayList<>();
        check(breaches, "LATE_SHIPMENT_RATE", m.lateShipmentRate(), lateShipmentTarget);
        check(breaches, "CANCELLATION_RATE", m.cancellationRate(), cancellationTarget);
        check(breaches, "ORDER_DEFECT_RATE", m.defectRate(), defectTarget);
        return new SellerHealthReport(m, breaches.isEmpty() ? SellerHealthStatus.GOOD : SellerHealthStatus.AT_RISK,
                breaches, now);
    }

    private static void check(List<String> out, String name, Double value, double target) {
        if (value != null && value >= target) {
            out.add(String.format(Locale.ROOT, "%s %.2f%% ≥ %.2f%%", name, value * 100, target * 100));
        }
    }
}
