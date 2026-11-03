package com.afrotransact.deliveryruns.application;

import java.math.BigDecimal;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.FeeMethod;

/**
 * "What would a customer pay?" for the Delivery fees screen and for orders-service (carrier shipping).
 *
 * @param method         how the order is delivered
 * @param currency       ISO 4217
 * @param distanceMiles  AFROTRANSACT_DELIVERY: miles from the hub (null: the farthest band)
 * @param windowId       PICKUP / AFROTRANSACT_DELIVERY: apply this window's own fee, and its distance when no miles
 *                       are given
 * @param carrierQuoteMinor CARRIER: the carrier-gateway quote, if any
 * @param itemsMinor     CARRIER: goods subtotal (free-shipping threshold)
 */
public record FeePreviewCommand(FeeMethod method, String currency, BigDecimal distanceMiles, UUID windowId,
                                Long carrierQuoteMinor, Long itemsMinor) {
}
