package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.orders.domain.ShipTo;

/**
 * An order as other services need it (tax-returns, trust-ops): who bought it, the payment, the full ship-to address,
 * and every shipment with its lines, amounts, tax details and return window. Never contains handover codes.
 */
public record OrderLookup(String orderId, String orderNumber, String customerId, String status, String currency,
                          String paymentId, Instant placedAt, long itemsMinor, long shippingMinor, long taxMinor,
                          long discountMinor, long totalMinor, ShipTo shipTo, List<Shipment> shipments) {

    /**
     * One shipment.
     *
     * @param shippingMinor      shipping of the shipment (charged on its first line)
     * @param shipBy             date by which it must be handed over
     * @param returnWindowEndsAt end of the return window (delivered shipments with returnable lines)
     */
    public record Shipment(String shipmentId, int seq, String sellerOrgId, String sellerName, String fulfilmentType,
                           String deliveryMethod, String nodeId, String status, boolean perishable,
                           long shippingMinor, long totalMinor, String carrier, String trackingNumber,
                           LocalDate promisedBy, LocalDate shipBy, Instant deliveredAt, boolean captured,
                           Instant returnWindowEndsAt, List<Line> lines) {
    }

    /**
     * One line (the line id is also the payments line id).
     *
     * @param shippingMinor shipping charged on this line (its stored allocation of the shipment's shipping; the first
     *                      line carries all of it on orders placed before allocations were kept)
     * @param taxOutcome    EXEMPT or TAXABLE: the stored tax decision the sale used (null on orders placed before it
     *                      was kept); returns and refunds reverse with {@code internalTaxCode}, not a recompute
     * @param taxProvider   the tax method that priced the line (RULES, STRIPE_TAX, AVALARA)
     */
    public record Line(String lineId, int lineNo, String variantAtin, String productAtin, String title,
                       String productType, String category, boolean perishable, int quantity, long unitPriceMinor,
                       long discountMinor, long taxMinor, long shippingMinor, long shippingTaxMinor,
                       String taxJurisdiction, String localTaxJurisdiction, String taxOutcome, String internalTaxCode,
                       String providerTaxCode, String taxRuleVersion, String taxProvider) {
    }
}
