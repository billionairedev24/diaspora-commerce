package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.LineEligibility;

/** What can be returned from an order, per shipment and line. */
public record ReturnEligibility(UUID orderId, String orderNumber, String currency, List<Shipment> shipments) {

    public ReturnEligibility {
        shipments = List.copyOf(shipments);
    }

    public record Shipment(UUID shipmentId, String sellerOrgId, String sellerName, FulfilmentType fulfilment,
                           DeliveryMethod delivery, Instant deliveredAt, List<Line> lines) {

        public Shipment {
            lines = List.copyOf(lines);
        }
    }

    public record Line(String lineId, String atin, String title, int quantity, long unitPriceMinor,
                       LineEligibility eligibility) {
    }
}
