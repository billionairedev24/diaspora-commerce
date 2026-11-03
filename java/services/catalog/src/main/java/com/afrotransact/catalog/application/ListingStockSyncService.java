package com.afrotransact.catalog.application;

/**
 * Mirrors the quantity the offers service holds for a seller-fulfilled offer onto the catalog variant, so the seller's
 * product form, the staff console and every catalog read show the number shoppers can actually buy.
 */
public interface ListingStockSyncService {

    /** @return true when the variant's quantity changed */
    boolean mirror(String ownerOrg, String variantAtin, int quantity);
}
