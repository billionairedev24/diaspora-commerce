package com.afrotransact.taxreturns.api;

import java.util.List;
import java.util.Map;

/**
 * What the orders module asks tax-returns at checkout in-process: the tax of each line of a cart or order, as
 * {@code POST /v1/tax/quotes} answers it (scope {@code tax:quote}). Entered under the caller's in-process principal; the quote
 * use case checks the scope as for a token and a caller without it is refused with
 * {@code com.afrotransact.module.ModuleApiException} status 403.
 */
public interface TaxQuoteApi {

    Quote quote(QuoteRequest request);

    /** @param orderId the order or cart id; shadow mode compares only quotes that carry one (may be null) */
    record QuoteRequest(String currency, Place shipTo, List<Line> lines, String orderId) {

        public QuoteRequest {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** A postal place; {@code line1} only when the caller has it. */
    record Place(String country, String state, String postalCode, String city, String line1) {
    }

    /** @param shipFrom where the line ships from (null when unknown); {@code source} is SELLER_LOCATION or FULFILMENT_CENTRE */
    record Line(String lineId, String atin, String sellerOrgId, String productType, int quantity, long unitPriceMinor,
                long discountMinor, long shippingMinor, Map<String, Object> sellerBehaviour, ShipFrom shipFrom) {

        public Line {
            sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        }
    }

    record ShipFrom(String line1, String city, String state, String postalCode, String country, String source) {
    }

    record Quote(String provider, List<LineTax> lines) {

        public Quote {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** @param decisionStatus RESOLVED or the reason the line carries no figure; anything but RESOLVED blocks checkout */
    record LineTax(String lineId, long taxMinor, long shippingTaxMinor, String jurisdiction, String localJurisdiction,
                   String decisionStatus, String outcome, String internalTaxCode, String providerTaxCode,
                   String ruleVersion, String blockedReason, String addressPrecision) {
    }
}
