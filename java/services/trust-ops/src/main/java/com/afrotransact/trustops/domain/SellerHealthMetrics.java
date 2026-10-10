package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Counts over a rolling window of shipments placed with a seller (from orders events) and claims opened against it.
 *
 * @param shipments           shipments placed in the window
 * @param delivered           of which delivered
 * @param lateDeliveries      delivered after the promised date
 * @param sellerCancellations cancelled by the seller (or by the system for the seller, for example unconfirmed)
 * @param claims              disputes / A-to-z claims opened against the seller in the window
 */
public record SellerHealthMetrics(String orgId, Instant windowStart, Instant windowEnd, int shipments, int delivered,
                                  int lateDeliveries, int sellerCancellations, int claims) {

    public SellerHealthMetrics {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
    }

    /** Late deliveries / delivered; null when nothing was delivered. */
    public Double lateShipmentRate() {
        return delivered == 0 ? null : (double) lateDeliveries / delivered;
    }

    /** 1 − late shipment rate; null when unknown. */
    public Double onTimeRate() {
        Double late = lateShipmentRate();
        return late == null ? null : 1.0 - late;
    }

    /** Seller cancellations / shipments; null without shipments. */
    public Double cancellationRate() {
        return shipments == 0 ? null : (double) sellerCancellations / shipments;
    }

    /** Order defect rate: claims / shipments (capped at 1); null without shipments. */
    public Double defectRate() {
        return shipments == 0 ? null : Math.min(1.0, (double) claims / shipments);
    }
}
