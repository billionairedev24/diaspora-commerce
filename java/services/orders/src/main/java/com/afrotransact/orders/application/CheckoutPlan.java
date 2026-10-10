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

    /** One priced line (tax computed from the delivery address). */
    public record Line(String lineId, int lineNo, String variantAtin, String productAtin, String title,
                       String imageUrl, int quantity, long unitPriceMinor, long taxMinor, String category,
                       boolean perishable, String taxJurisdiction, int promiseDays) {
    }
}
