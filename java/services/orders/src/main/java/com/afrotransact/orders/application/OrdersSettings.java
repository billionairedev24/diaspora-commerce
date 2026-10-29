package com.afrotransact.orders.application;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Settings the use cases need (bound from {@code at.orders.*} in config).
 *
 * @param retailOrgId             AfroTransact Retail organisation (1P seller of record)
 * @param paymentTimeout          how long checkout waits for the payment authorisation (reservation TTL)
 * @param checkoutResponseTimeout how long POST /v1/checkout waits for the saga to reach "awaiting payment"
 * @param cancellationWindow      how long after placement a customer may cancel a shipment
 * @param perishableTypes         catalog product types treated as perishable
 * @param commissionCategories    catalog product type → payments commission category
 * @param followUpBatch           follow-up tasks per run
 * @param followUpMaxAttempts     attempts before a follow-up task is parked as FAILED
 * @param zone                    business time zone for promised dates
 */
public record OrdersSettings(String retailOrgId, Duration paymentTimeout,
                             Duration checkoutResponseTimeout, Duration cancellationWindow,
                             Set<String> perishableTypes, Map<String, String> commissionCategories,
                             int followUpBatch, int followUpMaxAttempts, String zone) {

    public OrdersSettings {
        perishableTypes = Set.copyOf(perishableTypes);
        commissionCategories = Map.copyOf(commissionCategories);
    }
}
