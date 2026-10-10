package com.afrotransact.inventory.domain;

import java.util.List;

/**
 * A fulfilment centre considered as the destination of an FBA inbound plan.
 *
 * @param temperatureZones storage zones of the FC (Ambient, Chilled …)
 * @param foreignSkus      plan SKUs another organisation already stocks there (stock is never mixed: excluded)
 * @param ownStockedSkus   plan SKUs the seller already stocks there (keeps a SKU's stock together)
 * @param unitsHeld        units on hand, held and inbound at the FC (spreads load)
 */
public record FcCandidate(String nodeId, List<String> temperatureZones, List<String> foreignSkus,
                          int ownStockedSkus, long unitsHeld) {

    public FcCandidate {
        temperatureZones = temperatureZones == null ? List.of() : List.copyOf(temperatureZones);
        foreignSkus = foreignSkus == null ? List.of() : List.copyOf(foreignSkus);
    }
}
