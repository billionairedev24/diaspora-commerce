package com.afrotransact.deliveryruns.application;

import java.util.UUID;

import com.afrotransact.deliveryruns.domain.DropMode;

/**
 * Economics of a run being planned. Nulls take the location's defaults; {@code expectedOrders} defaults to the
 * minimum.
 */
public record EconomicsPreviewCommand(UUID locationId, Long feeMinor, Integer minOrders, Integer capacityOrders,
                                      DropMode dropMode, Integer expectedOrders, Long commissionShareMinor) {
}
