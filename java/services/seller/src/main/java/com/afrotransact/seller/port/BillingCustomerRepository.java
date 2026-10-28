package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.Optional;

/** The Stripe customer that owns an organisation's saved payment methods (plain Payments, no Stripe Billing). */
public interface BillingCustomerRepository {

    Optional<String> find(String orgId);

    /** Stores the customer id; keeps the first one when two requests race. Returns the stored id. */
    String save(String orgId, String stripeCustomerId, Instant at);
}
