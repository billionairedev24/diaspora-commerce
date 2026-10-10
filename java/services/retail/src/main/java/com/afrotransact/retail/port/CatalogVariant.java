package com.afrotransact.retail.port;

/** A live catalog variant as seen through catalog's public API. */
public record CatalogVariant(String atin, String productAtin, String title, String ownerKind, String gtin,
                             String sellerSku) {
}
