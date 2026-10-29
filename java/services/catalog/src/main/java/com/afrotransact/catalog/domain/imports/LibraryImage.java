package com.afrotransact.catalog.domain.imports;

import com.afrotransact.catalog.domain.ProductImage;

/**
 * An image of the organisation's image library (media), matched to listings by its file name: {@code SKU.MAIN.jpg}
 * is the main image of SKU, {@code SKU.PT01}…{@code PT08} the additional images, {@code SKU.SWCH} the swatch.
 *
 * @param sku    the SKU parsed from the file name (upper case), null when the name does not follow the convention
 * @param slot   MAIN, PT01–PT08 or SWCH; null with {@code sku}
 * @param status READY (published) or FLAGGED (held by moderation)
 * @param url    the public URL when READY
 */
public record LibraryImage(String assetId, String fileName, String sku, String slot, String status, String url,
                           int width, int height, long bytes, String contentType) {

    public boolean ready() {
        return "READY".equals(status) && url != null && !url.isBlank();
    }

    public boolean held() {
        return "FLAGGED".equals(status);
    }

    /** A product or variant image slot (not the swatch). */
    public boolean productSlot() {
        return slot != null && !"SWCH".equals(slot);
    }

    public ProductImage toProductImage() {
        return new ProductImage(url, width, height, bytes, contentType == null ? "" : contentType);
    }
}
