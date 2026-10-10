package com.afrotransact.offers.port;

import java.util.Optional;
import java.util.UUID;

/** catalog-service lookups used to validate the product an offer is attached to. */
public interface CatalogClient {

    /** The live variant with this variant ATIN. */
    Optional<CatalogVariant> liveVariant(String atin);

    /** The live variant that carries this GTIN (UPC/EAN/ISBN digits). */
    Optional<CatalogVariant> liveVariantByGtin(String gtin);

    /** A sellable catalog variant and the product page it belongs to. */
    record CatalogVariant(UUID productId, String productAtin, String variantAtin, String gtin, UUID categoryId,
                          String title, String ownerOrgId) {
    }

    /** catalog-service could not be reached or answered with a server error. */
    class CatalogUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CatalogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
