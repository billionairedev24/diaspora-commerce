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

    /**
     * The organisation's SKU list for naming library images: one line per variant with its SKU (the file name stem:
     * SKU.MAIN.jpg, SKU.PT01.jpg), whether the SKU was generated, its ATIN, barcode, product title and options.
     */
    List<SkuLine> skuList();

    record SkuLine(String sku, boolean generated, String atin, String gtin, String title, String options) {
    }

    record AttachResult(int productsUpdated, int imagesAttached, List<Skipped> skipped) {
    }

    record Skipped(String sku, String reason) {
    }

    record Usage(List<ImageUse> usage, List<String> knownSkus) {
    }

    record ImageUse(String url, List<String> skus) {
    }
}
