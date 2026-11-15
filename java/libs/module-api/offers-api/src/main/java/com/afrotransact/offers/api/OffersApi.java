package com.afrotransact.offers.api;

import java.util.List;
import java.util.UUID;

/**
 * What other modules may ask offers in-process: the bulk upload writes and the presence check catalog makes over HTTP today
 * ({@code POST /v1/offers/batch}, {@code PUT /v1/offers/prices-and-stock}, scope {@code offers:write}; {@code GET /v1/offers/presence},
 * scope {@code offers:read}).
 *
 * <p>Every call must run under an in-process caller principal ({@code com.afrotransact.module.InProcessCalls}); the same
 * permission checks as the HTTP endpoints apply and a caller without the scope is refused with the platform's
 * {@code ForbiddenActionException} (HTTP 403).
 */
public interface OffersApi {

    /** Creates each offer, or changes the organisation's open offer on the same ATIN, condition and channel; one result per row. */
    List<RowResult> upsert(String sellerOrgId, UUID importId, String uploadedBy, List<OfferTerms> rows);

    /** Changes price, list price and stock by SKU or ATIN; null fields stay as they are; one result per row. */
    List<RowResult> setPricesAndStock(String sellerOrgId, UUID importId, String uploadedBy, List<PriceStock> rows);

    /** Whether the organisation has an open (not withdrawn) offer on the page or on any of its variants. */
    boolean hasOpenOffer(String sellerOrgId, String atin);

    record OfferTerms(String rowKey, String atin, String sellerSku, String condition, long priceMinor,
                      Long listPriceMinor, Long shippingMinor, String fulfilment, Integer quantity, Integer handlingDays,
                      ShipFromPlace shipFrom, String conditionNote, PackageBox packaging) {
    }

    record ShipFromPlace(String city, String region, String country, String postalCode) {
    }

    record PackageBox(double lengthIn, double widthIn, double heightIn, double weightLb) {
    }

    record PriceStock(String rowKey, String sellerSku, String atin, Long priceMinor, Long listPriceMinor,
                      Integer quantity, String gtin) {
    }

    /**
     * @param outcome CREATED, UPDATED, UNCHANGED, HELD or FAILED
     * @param status  the offer's status after the row; null for FAILED
     */
    record RowResult(String rowKey, String outcome, UUID offerId, String atin, String status, String code,
                     String message) {
    }
}
