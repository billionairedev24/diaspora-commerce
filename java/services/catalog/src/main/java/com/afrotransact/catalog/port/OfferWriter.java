package com.afrotransact.catalog.port;

import java.util.List;
import java.util.UUID;

/**
 * Creates and changes offers for a bulk upload (S393, S394) through offers-service batch endpoints. Catalog never stores an
 * offer or sets its status: offers-service applies the same checks as a single offer (selling approval, condition, listing
 * cap) and the tax rule, and says whether the offer is for sale or held.
 */
public interface OfferWriter {

    /**
     * Creates each offer, or changes the seller's open offer on the same ATIN, condition and channel. Sending the same
     * rows again changes nothing.
     *
     * @throws OffersUnavailable when offers-service did not answer; nothing may be assumed saved, the rows stay pending
     */
    List<Result> upsert(String sellerOrgId, UUID importId, String uploadedBy, List<OfferTerms> rows);

    /** Changes price, list price and stock by SKU or ATIN; null fields stay as they are. */
    List<Result> setPricesAndStock(String sellerOrgId, UUID importId, String uploadedBy, List<PriceStock> rows);

    /** The offer terms of one row; {@code rowKey} comes back in the result. */
    record OfferTerms(String rowKey, String atin, String sellerSku, String condition, long priceMinor,
                      Long listPriceMinor, Long shippingMinor, String fulfilment, Integer quantity, Integer handlingDays,
                      ShipFrom shipFrom, String conditionNote, Box packaging) {
    }

    record ShipFrom(String city, String region, String country, String postalCode) {
    }

    record Box(double lengthIn, double widthIn, double heightIn, double weightLb) {
    }

    record PriceStock(String rowKey, String sellerSku, String atin, Long priceMinor, Long listPriceMinor,
                      Integer quantity) {
    }

    /**
     * @param outcome CREATED, UPDATED, UNCHANGED, HELD (stored but waiting for the tax decision) or FAILED
     * @param code    a stable code for FAILED and HELD rows (for example {@code offers.selling-approval-required})
     * @param message plain words for the seller
     */
    record Result(String rowKey, String outcome, UUID offerId, String atin, String status, String code, String message) {
    }

    /** offers-service did not answer. */
    class OffersUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public OffersUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
