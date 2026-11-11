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
 * @param dangerousGoods the answer to the dangerous goods question (aerosols, flammables, batteries, candles, cleaners);
 *                     null when the category does not ask. True marks the listing: fulfilment by AfroTransact and
 *                     carrier-gated flows read it
 * @param documents    the documents uploaded on the form, marked pending review (reviewed by the approval engine)
 * @param exemptions   rule field id to the reason the seller gave for not providing a "required or exemption" field
 * @param sellerBehaviour the seller's answers to the tax questions about how this seller sells the item (sold heated,
 *                     heated by seller, utensils provided); typed, by fact id
 */
public record ListingDetails(String description, ItemCondition condition, List<String> keywords,
                             FulfilmentMode fulfilment, PackageDimensions packaging, int handlingDays,
                             ShipFromRef shipFrom, UUID sizeChartId, FitNote fitNote,
                             Map<String, Object> sellerBehaviour, Boolean dangerousGoods,
                             List<ListingDocument> documents, Map<String, String> exemptions) {

    public static final int MAX_DESCRIPTION = 5000;
    public static final int MAX_KEYWORDS = 20;
    public static final int MAX_KEYWORD = 40;
    public static final int DEFAULT_HANDLING_DAYS = 2;
    public static final int MAX_HANDLING_DAYS = 30;
    public static final ListingDetails DEFAULT = new ListingDetails(null, ItemCondition.NEW, List.of(),
            FulfilmentMode.SELLER, null, DEFAULT_HANDLING_DAYS, null, null, null, null);

    /** Listing details with the seller's behaviour answers but without the rule answers. */
    public ListingDetails(String description, ItemCondition condition, List<String> keywords,
                          FulfilmentMode fulfilment, PackageDimensions packaging, int handlingDays,
                          ShipFromRef shipFrom, UUID sizeChartId, FitNote fitNote,
                          Map<String, Object> sellerBehaviour) {
        this(description, condition, keywords, fulfilment, packaging, handlingDays, shipFrom, sizeChartId, fitNote,
                sellerBehaviour, null, null, null);
    }

    /** Listing details without seller behaviour answers. */
    public ListingDetails(String description, ItemCondition condition, List<String> keywords,
                          FulfilmentMode fulfilment, PackageDimensions packaging, int handlingDays,
                          ShipFromRef shipFrom, UUID sizeChartId, FitNote fitNote) {
        this(description, condition, keywords, fulfilment, packaging, handlingDays, shipFrom, sizeChartId, fitNote, null);
    }

    /** These details with the seller's behaviour answers. */
    public ListingDetails withSellerBehaviour(Map<String, Object> behaviour) {
        return new ListingDetails(description, condition, keywords, fulfilment, packaging, handlingDays, shipFrom,
                sizeChartId, fitNote, behaviour, dangerousGoods, documents, exemptions);
    }

    /** These details with the answers the listing rules ask for: dangerous goods, documents and exemption reasons. */
    public ListingDetails withRuleAnswers(Boolean dangerous, List<ListingDocument> docs, Map<String, String> reasons) {
        return new ListingDetails(description, condition, keywords, fulfilment, packaging, handlingDays, shipFrom,
                sizeChartId, fitNote, sellerBehaviour, dangerous, docs, reasons);
    }

    /** These details with another condition. */
    public ListingDetails withCondition(ItemCondition next) {
        return new ListingDetails(description, next, keywords, fulfilment, packaging,
                handlingDays, shipFrom, sizeChartId, fitNote, sellerBehaviour, dangerousGoods, documents, exemptions);
    }

    /** These details with another listing-level package (the default of variants without their own). */
    public ListingDetails withPackaging(PackageDimensions next) {
        return new ListingDetails(description, condition, keywords, fulfilment, next, handlingDays, shipFrom,
                sizeChartId, fitNote, sellerBehaviour, dangerousGoods, documents, exemptions);
    }

    /** These details without ship-from, size chart and fit note. */
    public ListingDetails withoutShipFromAndSizing(boolean dropShipFrom, boolean dropSizing) {
        return new ListingDetails(description, condition, keywords, fulfilment, packaging, handlingDays,
                dropShipFrom ? null : shipFrom, dropSizing ? null : sizeChartId, dropSizing ? null : fitNote, sellerBehaviour,
                dangerousGoods, documents, exemptions);
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
                chartId, fit, sellerBehaviour, dangerousGoods, documents, exemptions);
    }

    public ListingDetails {
        description = description == null || description.isBlank() ? null : description.strip();
        condition = condition == null ? ItemCondition.NEW : condition;
        keywords = keywords == null ? List.of() : keywords.stream().map(String::strip).filter(k -> !k.isEmpty())
                .distinct().toList();
        fulfilment = fulfilment == null ? FulfilmentMode.SELLER : fulfilment;
        handlingDays = handlingDays <= 0 ? DEFAULT_HANDLING_DAYS : handlingDays;
        sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        documents = documents == null ? List.of() : List.copyOf(documents);
        exemptions = exemptions == null ? Map.of() : Map.copyOf(exemptions);
    }
}
