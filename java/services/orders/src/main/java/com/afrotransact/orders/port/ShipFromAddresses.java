package com.afrotransact.orders.port;

import java.util.Optional;

import com.afrotransact.orders.domain.FulfilmentType;

/**
 * Where a shipment ships from, for tax-returns (Avalara prices by origin and destination): the AfroTransact fulfilment
 * centre that holds the stock (inventory {@code GET /v1/nodes/{id}/address}), the seller's pickup location of an
 * AFROTRANSACT_DELIVERY offer (seller {@code GET /v1/ship-from-locations/{id}}) or, for a seller-fulfilled shipment, the
 * seller's default ship-from address (seller {@code GET /v1/orgs/{orgId}/default-ship-from-location}).
 *
 * <p>Best effort: an unknown place or an upstream that cannot answer gives {@link Optional#empty()} and never fails the
 * checkout. The tax quote then simply carries no ship-from for the line, and a provider that needs one is not asked.
 */
public interface ShipFromAddresses {

    /** Never knows an origin (tests and deployments without the lookups). */
    ShipFromAddresses NONE = (type, sellerOrgId, nodeId, pickupLocationId) -> Optional.empty();

    Optional<TaxQuoteClient.ShipFrom> forShipment(FulfilmentType type, String sellerOrgId, String nodeId,
                                                  String pickupLocationId);
}
