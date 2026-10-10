package com.afrotransact.ai.domain.lola;

/**
 * A product exactly as a tool returned it. Lola may only show cards built from tool results; price and stock always
 * come from here, never from model text.
 *
 * @param atin          product or variant ATIN ({@code AT} + 8 base-36 characters)
 * @param title         product title (sanitised seller text)
 * @param brand         brand, may be empty
 * @param sellerName    seller display name
 * @param priceMinor    price in minor units
 * @param currency      ISO currency
 * @param inStock       sellable stock exists
 * @param availableQty  sellable units when known (0 when unknown)
 * @param lowStock      1–5 units left
 * @param deliveryDays  promised delivery days when known (0 when unknown)
 * @param rating        average rating (0 when unrated)
 * @param imageUrl      main image, may be empty
 */
public record ProductCard(String atin, String title, String brand, String sellerName, long priceMinor,
                          String currency, boolean inStock, int availableQty, boolean lowStock, int deliveryDays,
                          double rating, String imageUrl) {

    public ProductCard {
        if (atin == null || atin.isBlank()) {
            throw new IllegalArgumentException("atin is required");
        }
        title = title == null ? "" : title;
        brand = brand == null ? "" : brand;
        sellerName = sellerName == null ? "" : sellerName;
        currency = currency == null || currency.isBlank() ? "USD" : currency;
        imageUrl = imageUrl == null ? "" : imageUrl;
    }
}
