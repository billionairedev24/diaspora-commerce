package com.afrotransact.seller.domain;

import java.util.List;

/**
 * Result of applying Stripe data to a connected account.
 *
 * @param newItems requirements that are open now but were not open before (notify the seller)
 */
public record ComplianceUpdate(ConnectAccount account, boolean requirementsChanged, boolean statusChanged,
                               List<String> newItems, Enforcement payouts, Enforcement listings) {

    public ComplianceUpdate {
        newItems = List.copyOf(newItems);
    }
}
