package com.afrotransact.orders.application;

/** A change of the shipping settings. {@code freeAboveMinor} null means never free. */
public record ShippingSettingsCommand(long flatFeeMinor, Long freeAboveMinor, boolean sellerCarrierShippingEnabled,
                                      String reason) {
}
