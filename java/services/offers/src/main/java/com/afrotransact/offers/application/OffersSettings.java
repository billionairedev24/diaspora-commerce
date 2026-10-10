package com.afrotransact.offers.application;

/**
 * Service settings ({@code at.offers.*}).
 *
 * @param retailOrgId             organisation id of AfroTransact Retail (first party: seller kind PLATFORM)
 * @param defaultHandlingDays     handling time given to offers created from catalog events
 * @param afrotransactTransitDays transit estimate for Fulfilled by AfroTransact offers (buy box delivery speed)
 * @param sellerTransitDays       transit estimate for seller-fulfilled offers
 * @param lowStockThreshold       1..n available units are LOW_STOCK
 * @param afrotransactDeliveryTransitDays transit estimate for AfroTransact Delivery from the seller's store (S255)
 */
public record OffersSettings(String retailOrgId, int defaultHandlingDays, int afrotransactTransitDays,
                             int sellerTransitDays, int lowStockThreshold, int afrotransactDeliveryTransitDays) {

    /** Settings with the default local-delivery transit (1 day). */
    public OffersSettings(String retailOrgId, int defaultHandlingDays, int afrotransactTransitDays,
                          int sellerTransitDays, int lowStockThreshold) {
        this(retailOrgId, defaultHandlingDays, afrotransactTransitDays, sellerTransitDays, lowStockThreshold, 1);
    }
}
