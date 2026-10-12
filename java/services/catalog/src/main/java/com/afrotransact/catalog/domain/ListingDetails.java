package com.afrotransact.catalog.domain;

import java.util.List;

/**
 * The listing details of the one-step product form beyond title, attributes and variants: shopper description,
 * condition, search keywords, fulfilment, package, handling time and ship-from location.
 *
 * @param handlingDays business days from order to dispatch (1–30)
 * @param packaging    required when {@code fulfilment} is AFROTRANSACT; may be null otherwise
 * @param shipFrom     seller-fulfilled listings only; null = the seller's default ship-from address
 */
public record ListingDetails(String description, ItemCondition condition, List<String> keywords,
                             FulfilmentMode fulfilment, PackageDimensions packaging, int handlingDays,
                             ShipFromRef shipFrom) {

    public static final int MAX_DESCRIPTION = 5000;
    public static final int MAX_KEYWORDS = 20;
    public static final int MAX_KEYWORD = 40;
    public static final int DEFAULT_HANDLING_DAYS = 2;
    public static final int MAX_HANDLING_DAYS = 30;
    public static final ListingDetails DEFAULT = new ListingDetails(null, ItemCondition.NEW, List.of(),
            FulfilmentMode.SELLER, null, DEFAULT_HANDLING_DAYS, null);

    public ListingDetails {
        description = description == null || description.isBlank() ? null : description.strip();
        condition = condition == null ? ItemCondition.NEW : condition;
        keywords = keywords == null ? List.of() : keywords.stream().map(String::strip).filter(k -> !k.isEmpty())
                .distinct().toList();
        fulfilment = fulfilment == null ? FulfilmentMode.SELLER : fulfilment;
        handlingDays = handlingDays <= 0 ? DEFAULT_HANDLING_DAYS : handlingDays;
    }
}
