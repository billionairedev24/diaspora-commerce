package com.afrotransact.catalog.port;

/** What offers-service knows about who sells a product page: only sellers with an offer may suggest an edit. */
public interface SellerOffers {

    /**
     * Whether the organisation has an open (not withdrawn) offer on the page or on any of its variants.
     *
     * @param atin the page ATIN or a variant ATIN
     * @throws OffersUnavailable when offers-service cannot be asked: callers refuse (fail closed)
     */
    boolean hasOpenOffer(String sellerOrgId, String atin);

    /** offers-service did not answer. */
    class OffersUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public OffersUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
