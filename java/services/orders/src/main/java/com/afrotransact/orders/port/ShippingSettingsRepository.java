package com.afrotransact.orders.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.orders.domain.ShippingSettings;
import com.afrotransact.orders.domain.ShippingSettingsChange;

/** The shipping settings of each currency and their change log. */
public interface ShippingSettingsRepository {

    Optional<ShippingSettings> find(String currency);

    /** Reads the record and locks it for the rest of the transaction. */
    Optional<ShippingSettings> lock(String currency);

    void update(ShippingSettings next);

    void insert(ShippingSettings first);

    void log(ShippingSettingsChange change);

    /** Newest first. */
    List<ShippingSettingsChange> changes(String currency, int limit);
}
