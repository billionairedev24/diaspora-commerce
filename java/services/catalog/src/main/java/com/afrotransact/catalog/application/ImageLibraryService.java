package com.afrotransact.catalog.application;

import java.util.List;

/**
 * The seller's image library seen from the catalog (S310): attach library images named after SKUs to existing
 * listings, which listings use an image, and the brands the product form suggests. Members of the organisation with
 * catalog:write.
 */
public interface ImageLibraryService {

    /**
     * Applies the library images named after the listings' SKUs: SKU.MAIN and SKU.PT01–PT08 of any of a product's SKUs
     * replace its images (the first SKU with images wins); a variant's own SKU.MAIN sets that variant's image on
     * products with several variants. Held images are skipped; taken-down listings and main images under 1000 px are
     * skipped with a reason.
     *
     * @param skus only listings with one of these SKUs; empty for every listing of the organisation
     */
    AttachResult attach(List<String> skus);

    /** Which of the organisation's SKUs use these image URLs, and which of these SKUs exist. */
    Usage usage(List<String> urls, List<String> skus);

    /** The organisation's brands (from its products' brand attribute), most used first; q filters by word prefix. */
    List<BrandUse> brands(String q, int limit);

    record AttachResult(int productsUpdated, int imagesAttached, List<Skipped> skipped) {
    }

    record Skipped(String sku, String reason) {
    }

    record Usage(List<ImageUse> usage, List<String> knownSkus) {
    }

    record ImageUse(String url, List<String> skus) {
    }

    record BrandUse(String name, int products) {
    }
}
