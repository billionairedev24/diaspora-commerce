package com.afrotransact.orders.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * The staff-set shipping charge (listing rules D16): one flat fee for Delivery by AfroTransact and for AfroTransact
 * collecting from a seller's store, an optional free-above threshold on the package's items, and the switch for
 * seller-shipped carrier options (off at launch). The single source of the fee: the shopper estimate (gateway-bff) and
 * the amount orders charges both come from this record, and {@link #deliveryFee(long)} is the one formula.
 *
 * @param flatFeeMinor                 0 to {@value #MAX_FEE_MINOR} minor units
 * @param freeAboveMinor               the package items subtotal from which delivery is free; null = never free
 * @param sellerCarrierShippingEnabled carrier shipping for seller-shipped packages (off at launch)
 * @param version                      1 for the launch default, then +1 per change
 */
public record ShippingSettings(String currency, long flatFeeMinor, Long freeAboveMinor,
                               boolean sellerCarrierShippingEnabled, long version, Instant updatedAt, String updatedBy,
                               String reason) {

    /** The largest delivery fee staff may set (100.00). */
    public static final long MAX_FEE_MINOR = 10_000;
    /** The largest free-above threshold (100,000.00). */
    public static final long MAX_FREE_ABOVE_MINOR = 10_000_000;

    public ShippingSettings {
        Objects.requireNonNull(currency, "currency");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (flatFeeMinor < 0 || flatFeeMinor > MAX_FEE_MINOR) {
            throw new OrdersRuleViolation("orders.invalid-shipping-settings",
                    "The delivery fee must be between 0 and 100.00 (0 is free)");
        }
        if (freeAboveMinor != null && (freeAboveMinor <= 0 || freeAboveMinor > MAX_FREE_ABOVE_MINOR)) {
            throw new OrdersRuleViolation("orders.invalid-shipping-settings",
                    "Free delivery above must be more than 0, or left empty for never");
        }
    }

    /** The delivery charge of one package whose items cost {@code itemsMinor}. */
    public long deliveryFee(long itemsMinor) {
        return freeAboveMinor != null && itemsMinor >= freeAboveMinor ? 0 : flatFeeMinor;
    }

    /** The next version of this record. */
    public ShippingSettings next(long flatFee, Long freeAbove, boolean carrierEnabled, String actor, String why,
                                 Instant at) {
        return new ShippingSettings(currency, flatFee, freeAbove, carrierEnabled, version + 1, at, actor, why);
    }

    /** Whether the numbers and the switch are the same (a save that changes nothing makes no version). */
    public boolean sameAs(long flatFee, Long freeAbove, boolean carrierEnabled) {
        return flatFeeMinor == flatFee && Objects.equals(freeAboveMinor, freeAbove)
                && sellerCarrierShippingEnabled == carrierEnabled;
    }
}
