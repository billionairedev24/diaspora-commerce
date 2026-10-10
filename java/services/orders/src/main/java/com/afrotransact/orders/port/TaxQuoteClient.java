package com.afrotransact.orders.port;

import java.util.List;

/**
 * tax-returns-service {@code POST /v1/tax/quotes} (orders-service token with {@code tax:quote}): marketplace-facilitator
 * sales tax per line for one ship-to address. Throws {@link UpstreamFailure} when tax-returns is down or refuses.
 */
public interface TaxQuoteClient {

    Quote quote(Request request);

    /** Lines shipped to one address. */
    record Request(String currency, String country, String state, String postalCode, String city, List<Line> lines) {

        public Request {
            lines = List.copyOf(lines);
        }
    }

    /**
     * One order line.
     *
     * @param shippingMinor shipping charged on this line (a shipment's shipping sits on its first line)
     */
    record Line(String lineId, String atin, String sellerOrgId, String productType, int quantity,
                long unitPriceMinor, long discountMinor, long shippingMinor) {
    }

    /** The quote: one result per requested line. */
    record Quote(String provider, List<LineTax> lines) {

        public Quote {
            lines = List.copyOf(lines);
        }
    }

    /**
     * Tax of one line.
     *
     * @param shippingTaxMinor the part of {@code taxMinor} charged on the line's shipping
     */
    record LineTax(String lineId, long taxMinor, long shippingTaxMinor, String jurisdiction,
                   String localJurisdiction) {
    }
}
