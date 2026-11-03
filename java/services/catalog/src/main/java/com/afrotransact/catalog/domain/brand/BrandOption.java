package com.afrotransact.catalog.domain.brand;

import java.util.UUID;

/**
 * A brand offered on the product form.
 *
 * @param ownProducts the asking organisation's listings that hold it
 * @param products    live listings of every seller that hold it (popularity)
 */
public record BrandOption(UUID id, String name, BrandStatus status, String createdByOrg, long ownProducts,
                          long products) {
}
