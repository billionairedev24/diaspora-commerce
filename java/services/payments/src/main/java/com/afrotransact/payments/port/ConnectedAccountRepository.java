package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.Optional;

/** Stripe Connect account of each seller / provider organisation (manual payout schedule). */
public interface ConnectedAccountRepository {

    void upsert(String orgId, String stripeAccountId, Instant now);

    Optional<String> find(String orgId);
}
