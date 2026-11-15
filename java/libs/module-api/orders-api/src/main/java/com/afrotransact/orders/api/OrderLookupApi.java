package com.afrotransact.orders.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Order lookups other modules make over HTTP today, entered in-process under the calling module's principal:
 * {@code GET /v1/lookup/orders/{idOrNumber}} (scope {@code orders:lookup}) and
 * {@code GET /v1/lookup/customers/{customerId}/purchases/{atin}} (scope {@code orders:purchase-check}). A caller without the
 * scope is refused with {@code ModuleApiException} status 403; an unknown order is status 404.
 */
public interface OrderLookupApi {

    /** An order by id or number with its shipments and lines; never contains handover codes. */
    OrderView lookup(String idOrNumber);

    /** Did the customer receive a product (verified-purchase reviews): the delivering order, never its contents. */
    PurchaseProof purchase(String customerId, String productAtin);

    /** @param delivered true when a shipment with a line for the product ATIN was delivered to the customer */
    record PurchaseProof(boolean delivered, String orderId, String orderNumber, String shipmentId, Instant deliveredAt) {
    }

    record OrderView(String orderId, String orderNumber, String customerId, String status, String currency,
                     String paymentId, Instant placedAt, long itemsMinor, long shippingMinor, long taxMinor,
                     long discountMinor, long totalMinor, ShipTo shipTo, List<Shipment> shipments) {

        public OrderView {
            shipments = shipments == null ? List.of() : List.copyOf(shipments);
        }
    }

    record ShipTo(String fullName, String phone, String line1, String line2, String city, String state, String postalCode,
                  String country) {
    }

    record Shipment(String shipmentId, int seq, String sellerOrgId, String sellerName, String fulfilmentType,
                    String deliveryMethod, String nodeId, String status, boolean perishable, long shippingMinor,
                    long totalMinor, String carrier, String trackingNumber, LocalDate promisedBy, LocalDate shipBy,
                    Instant deliveredAt, boolean captured, Instant returnWindowEndsAt, List<Line> lines) {

        public Shipment {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    record Line(String lineId, int lineNo, String variantAtin, String productAtin, String title, String productType,
                String category, boolean perishable, int quantity, long unitPriceMinor, long discountMinor, long taxMinor,
                long shippingMinor, long shippingTaxMinor, String taxJurisdiction, String localTaxJurisdiction,
                String taxOutcome, String internalTaxCode, String providerTaxCode, String taxRuleVersion,
                String taxProvider) {
    }
}
