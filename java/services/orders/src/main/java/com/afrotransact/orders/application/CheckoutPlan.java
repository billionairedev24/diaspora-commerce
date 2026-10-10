package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;

/** Validated, priced and taxed checkout, one package per seller and fulfilment type (before node split). */
public record CheckoutPlan(String currency, List<Package> packages) {

    /** One package with its delivery choice. */
    public record Package(String packageId, String sellerOrgId, String sellerName, FulfilmentType fulfilmentType,
                          DeliveryMethod method, String serviceLevel, String pickupLocationId,
                          DeliveryPreferences preferences, int promiseDays, List<Line> lines) {
    }

    /**
     * One priced line (tax computed from the delivery address).
     *
     * @param offerId       offers-service offer (coupon line reference); null for catalog-only lines
     * @param discountMinor coupon discount allocated to the line
     */
    public record Line(String lineId, int lineNo, String variantAtin, String productAtin, String title,
                       String imageUrl, int quantity, long unitPriceMinor, long taxMinor, String category,
                       boolean perishable, String taxJurisdiction, int promiseDays, String offerId,
                       long discountMinor) {

        /** Unit price × quantity. */
        public long itemsMinor() {
            return Math.multiplyExact(unitPriceMinor, (long) quantity);
        }

        /** The line with a discount (capped at its items); tax is scaled to the discounted amount. */
        public Line withDiscount(long discount) {
            long items = itemsMinor();
            long d = Math.clamp(discount, 0, items);
            long tax = items == 0 ? taxMinor : Math.round((double) taxMinor * (items - d) / items);
            return new Line(lineId, lineNo, variantAtin, productAtin, title, imageUrl, quantity, unitPriceMinor, tax,
                    category, perishable, taxJurisdiction, promiseDays, offerId, d);
        }
    }
}
