package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.ShippingSettings;
import com.afrotransact.orders.domain.ShippingSettingsChange;

/**
 * The shipping settings as configuration (listing rules D16): staff set the one flat delivery fee, the optional
 * free-above threshold and the seller carrier switch. Reading is public to the platform (what delivery costs is shown to
 * every shopper); viewing the history needs {@code orders:shipping.view}; changing needs {@code orders:shipping.edit}
 * and a reason, logged with who and when.
 */
public interface ShippingSettingsService {

    /**
     * The settings in force, read from the database each time (never a guess): what checkout charges by. Fails (the
     * checkout step is retried, nothing is charged) when none can be read.
     */
    ShippingSettings current(String currency);

    /** The settings in force for the public estimate and the staff screen. */
    ShippingSettings view(String currency);

    /**
     * Sets the numbers and the carrier switch. 422 {@code orders.reason-required} without a reason (10 to 500
     * characters); 422 {@code orders.invalid-shipping-settings} for numbers out of range; 409
     * {@code orders.shipping-settings-version-mismatch} when {@code expectedVersion} is not the current version. A save
     * that changes nothing makes no new version.
     */
    ShippingSettings replace(String currency, ShippingSettingsCommand command, Long expectedVersion);

    /** The audit trail, newest first. */
    List<ShippingSettingsChange> changes(String currency, int limit);
}
