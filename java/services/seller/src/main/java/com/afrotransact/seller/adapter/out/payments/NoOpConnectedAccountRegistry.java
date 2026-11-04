package com.afrotransact.seller.adapter.out.payments;

import com.afrotransact.seller.port.ConnectedAccountRegistry;

/** Used when no payments URL is configured (tests). */
public final class NoOpConnectedAccountRegistry implements ConnectedAccountRegistry {

    @Override
    public void register(String orgId, String stripeAccountId) {
        // no-op
    }
}
