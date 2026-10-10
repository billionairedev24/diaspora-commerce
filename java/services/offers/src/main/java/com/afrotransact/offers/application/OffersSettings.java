package com.afrotransact.offers.application;

/**
 * Service settings ({@code at.offers.*}).
 *
 * @param retailOrgId             organisation id of AfroTransact Retail (first party: seller kind PLATFORM)
 * @param defaultHandlingDays     handling time given to offers created from catalog events
 * @param afrotransactTransitDays transit estimate for Fulfilled by AfroTransact offers (buy box delivery speed)
 * @param sellerTransitDays       transit estimate for seller-fulfilled offers
 * @param lowStockThreshold       1..n available units are LOW_STOCK
 */
public record OffersSettings(String retailOrgId, int defaultHandlingDays, int afrotransactTransitDays,
                             int sellerTransitDays, int lowStockThreshold) {
}
