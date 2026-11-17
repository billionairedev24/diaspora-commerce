package com.afrotransact.orders.application;

/**
 * A change of the shipping settings. {@code freeAboveMinor} null means never free. {@code sellerSelfShippingEnabled} is the
 * staff switch for sellers who ship themselves (false unless staff turn it on).
 */
public record ShippingSettingsCommand(long flatFeeMinor, Long freeAboveMinor, boolean sellerCarrierShippingEnabled,
                                      boolean sellerSelfShippingEnabled, String reason) {
}
