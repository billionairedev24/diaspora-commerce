package com.afrotransact.orders.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A seller's own shipping charge, used only while the staff switch {@link ShippingSettings#sellerSelfShippingEnabled()} is
 * on. The minimum rule (docs/engineering/seller-self-shipping-options.md, option A): <b>one flat charge per order package of
 * the seller</b>, set once here and never per product, so several items from one seller never add up to a fee nobody
 * chose. The seller sets it, keeps it and is responsible for it being fair; AfroTransact takes no commission on it.
 *
 * @param sellerOrgId the seller organisation
 * @param chargeMinor 0 to {@value ShippingSettings#MAX_FEE_MINOR} minor units (0 = the seller ships free)
 * @param enabled     the seller's own opt-in
 */
public record SellerSelfShipping(String sellerOrgId, String currency, long chargeMinor, boolean enabled, long version,
                                 Instant updatedAt, String updatedBy) {

    public SellerSelfShipping {
        Objects.requireNonNull(sellerOrgId, "sellerOrgId");
        Objects.requireNonNull(currency, "currency");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (chargeMinor < 0 || chargeMinor > ShippingSettings.MAX_FEE_MINOR) {
            throw new OrdersRuleViolation("orders.invalid-self-shipping-charge",
                    "The shipping charge must be between 0 and 100.00 (0 is free shipping)");
        }
    }

    /** The next version with a new charge and opt-in. */
    public SellerSelfShipping next(long charge, boolean optedIn, String actor, Instant at) {
        return new SellerSelfShipping(sellerOrgId, currency, charge, optedIn, version + 1, at, actor);
    }
}
