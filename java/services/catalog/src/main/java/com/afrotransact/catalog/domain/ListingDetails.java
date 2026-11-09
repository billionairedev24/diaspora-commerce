package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.sizing.FitNote;

/**
 * The listing details of the one-step product form beyond title, attributes and variants: shopper description,
 * condition, search keywords, fulfilment, package, handling time and ship-from location.
 *
 * @param handlingDays business days from order to dispatch (1–30)
 * @param packaging    required when {@code fulfilment} is AFROTRANSACT; may be null otherwise
 * @param shipFrom     seller-fulfilled listings only; null = the seller's default ship-from address
 * @param sizeChartId  the seller's size chart shown on the product page; null for none (see {@code SizeChartPolicy})
 * @param fitNote      how the sizes fit, shown beside the size chart; null for no statement
 * @param sellerBehaviour the seller's answers to the tax questions about how this seller sells the item (sold heated,
 *                     heated by seller, utensils provided); typed, by fact id
 */
public record ListingDetails(String description, ItemCondition condition, List<String> keywords,
                             FulfilmentMode fulfilment, PackageDimensions packaging, int handlingDays,
                             ShipFromRef shipFrom, UUID sizeChartId, FitNote fitNote,
                             Map<String, Object> sellerBehaviour) {

    public static final int MAX_DESCRIPTION = 5000;
    public static final int MAX_KEYWORDS = 20;
    public static final int MAX_KEYWORD = 40;
    public static final int DEFAULT_HANDLING_DAYS = 2;
    public static final int MAX_HANDLING_DAYS = 30;
    public static final ListingDetails DEFAULT = new ListingDetails(null, ItemCondition.NEW, List.of(),
            FulfilmentMode.SELLER, null, DEFAULT_HANDLING_DAYS, null, null, null, null);

    /** Listing details without seller behaviour answers. */
    public ListingDetails(String description, ItemCondition condition, List<String> keywords,
                          FulfilmentMode fulfilment, PackageDimensions packaging, int handlingDays,
                          ShipFromRef shipFrom, UUID sizeChartId, FitNote fitNote) {
        this(description, condition, keywords, fulfilment, packaging, handlingDays, shipFrom, sizeChartId, fitNote, null);
    }

    /** These details with the seller's behaviour answers. */
    public ListingDetails withSellerBehaviour(Map<String, Object> behaviour) {
        return new ListingDetails(description, condition, keywords, fulfilment, packaging, handlingDays, shipFrom,
                sizeChartId, fitNote, behaviour);
    }

    /** Listing details without a size chart. */
    public ListingDetails(String description, ItemCondition condition, List<String> keywords,
                          FulfilmentMode fulfilment, PackageDimensions packaging, int handlingDays,
                          ShipFromRef shipFrom) {
        this(description, condition, keywords, fulfilment, packaging, handlingDays, shipFrom, null, null);
    }

    /** These details linked to a size chart (null for none) and a fit note. */
    public ListingDetails withSizing(UUID chartId, FitNote fit) {
        return new ListingDetails(description, condition, keywords, fulfilment, packaging, handlingDays, shipFrom,
                chartId, fit, sellerBehaviour);
    }

    public ListingDetails {
        description = description == null || description.isBlank() ? null : description.strip();
        condition = condition == null ? ItemCondition.NEW : condition;
        keywords = keywords == null ? List.of() : keywords.stream().map(String::strip).filter(k -> !k.isEmpty())
                .distinct().toList();
        fulfilment = fulfilment == null ? FulfilmentMode.SELLER : fulfilment;
        handlingDays = handlingDays <= 0 ? DEFAULT_HANDLING_DAYS : handlingDays;
        sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
    }
}
