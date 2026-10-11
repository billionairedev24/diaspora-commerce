package com.afrotransact.offers.application;

import java.util.UUID;

/**
 * AfroTransact Retail's "list on storefront" (S281): retail-service's item listing (retail.item.listing-changed, and
 * retail.item.updated for a new ATIN link) drives the first-party offer catalog created for the linked variant. LIVE
 * points the offer's inventory SKU at the retail item SKU (the SKU retail receipts stock lots under), takes the
 * item's retail price and resumes a paused offer; PAUSED pauses it; DRAFT changes nothing. Inbox-deduplicated.
 */
public interface RetailListingSyncService {

    /** @return true when the platform offer changed */
    boolean itemChanged(UUID eventId, RetailItem item);

    /**
     * The fields of retail's RetailItemEvent that matter here.
     *
     * @param atin          linked catalog variant ATIN, or null
     * @param listingStatus DRAFT, LIVE or PAUSED
     */
    record RetailItem(UUID itemId, String sku, String atin, String listingStatus, Long priceMinor, String currency) {
    }
}
