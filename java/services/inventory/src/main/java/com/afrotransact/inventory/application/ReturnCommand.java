package com.afrotransact.inventory.application;

import java.util.UUID;

import com.afrotransact.inventory.domain.ReturnCondition;

/**
 * A returned item after inspection.
 *
 * @param lotId optional: the lot the unit came from; defaults to the lot allocated to the order line
 */
public record ReturnCommand(String orderId, String sku, UUID lotId, int qty, ReturnCondition condition,
                            String reason) {
}
