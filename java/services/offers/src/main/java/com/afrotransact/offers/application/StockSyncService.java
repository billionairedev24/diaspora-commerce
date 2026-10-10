package com.afrotransact.offers.application;

import java.util.UUID;

import com.afrotransact.offers.port.StockRepository.StockLevel;

/** Keeps the availability projection from inventory.availability.changed (inbox de-duplicated). */
public interface StockSyncService {

    void availabilityChanged(UUID eventId, StockLevel level);
}
