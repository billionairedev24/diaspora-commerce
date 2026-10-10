package com.afrotransact.inventory.application;

import java.util.UUID;

import com.afrotransact.inventory.domain.ReturnCondition;

/**
 * A returned item after inspection.
 *
 * @param lotId           optional: the lot the unit came from; defaults to the lot allocated to the order line
 * @param receivingNodeId optional: the warehouse that received the parcel (e.g. the return centre); defaults to
 *                        the node the unit shipped from. Units received elsewhere go into a lot of the same lot
 *                        number at the receiving node.
 */
public record ReturnCommand(String orderId, String sku, UUID lotId, int qty, ReturnCondition condition,
                            String reason, String receivingNodeId) {

    /** A return received at the node the unit shipped from. */
    public ReturnCommand(String orderId, String sku, UUID lotId, int qty, ReturnCondition condition, String reason) {
        this(orderId, sku, lotId, qty, condition, reason, null);
    }
}
