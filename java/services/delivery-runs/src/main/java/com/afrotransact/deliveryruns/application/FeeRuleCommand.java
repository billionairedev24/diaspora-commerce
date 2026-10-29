package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.FeeBand;

/**
 * Staff's numbers for one delivery fee rule. Fields a method does not use are ignored (null).
 *
 * @param bands          AfroTransact delivery: distance bands, contiguous from 0 miles
 * @param feeMinor       pickup: the fee
 * @param handlingMinor  carrier: added to the carrier-gateway quote
 * @param fallbackMinor  carrier: the fee when there is no quote
 * @param freeAboveMinor carrier: goods subtotal from which shipping is free (null = never)
 * @param reason         why (required; logged with who and when)
 */
public record FeeRuleCommand(List<FeeBand> bands, Long feeMinor, Long handlingMinor, Long fallbackMinor,
                             Long freeAboveMinor, String reason) {
}
