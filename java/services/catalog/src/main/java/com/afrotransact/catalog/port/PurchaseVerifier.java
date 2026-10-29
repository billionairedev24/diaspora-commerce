package com.afrotransact.catalog.port;

import java.util.Optional;

import com.afrotransact.catalog.domain.PurchaseProof;

/**
 * Asks orders-service whether a customer received a product ({@code GET /v1/lookup/customers/{customerId}/purchases/{atin}},
 * scope {@code orders:purchase-check}): the basis of "Verified purchase" (S324).
 */
public interface PurchaseVerifier {

    /**
     * The customer's delivered purchase of the product, or empty when there is none.
     *
     * @throws PurchaseCheckUnavailable when orders-service cannot answer (the review is then refused, not guessed)
     */
    Optional<PurchaseProof> deliveredPurchase(String customerId, String productAtin);

    /** Orders-service could not be asked; try again later. */
    class PurchaseCheckUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public PurchaseCheckUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
