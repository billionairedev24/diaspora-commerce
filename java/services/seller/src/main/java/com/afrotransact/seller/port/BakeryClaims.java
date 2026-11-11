package com.afrotransact.seller.port;

import java.util.List;

/**
 * Organisations whose listings say the seller is a bakery (the old {@code seller_is_bakery} answer, kept in the
 * catalog until it is migrated into the food service capability). Backed by catalog-service.
 */
public interface BakeryClaims {

    /** Organisation ids that claim to be a bakery on at least one listing. Throws when the catalog cannot be asked. */
    List<String> organisations();
}
