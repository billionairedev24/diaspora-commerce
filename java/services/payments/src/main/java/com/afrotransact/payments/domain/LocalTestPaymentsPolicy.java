package com.afrotransact.payments.domain;

import java.util.Set;

/**
 * When payments may take money without Stripe. Locally there is usually no Stripe test key, so the {@code local}
 * profile can use a stand-in that authorises every PaymentIntent the way Stripe's test card 4242 4242 4242 4242 does
 * (the storefront's labelled test card form); no money moves and nothing reaches Stripe. Every other environment
 * talks to Stripe: {@code dev}, {@code uat} and {@code prod} never get the stand-in, whatever the configuration says.
 */
public interface LocalTestPaymentsPolicy {

    /** {@code at.payments.stripe.mode}: always Stripe (the default everywhere). */
    String STRIPE = "stripe";

    /** {@code at.payments.stripe.mode}: always the local stand-in. */
    String LOCAL_TEST = "local-test";

    /** {@code at.payments.stripe.mode}: the local stand-in when no Stripe key is configured (the local profile's default). */
    String AUTO = "auto";

    /** The secret key application-local.yml falls back to when STRIPE_SECRET_KEY is not set. */
    String PLACEHOLDER_KEY_PREFIX = "sk_test_replace";

    /**
     * @param mode      {@link #STRIPE}, {@link #LOCAL_TEST} or {@link #AUTO} (blank = {@link #STRIPE})
     * @param secretKey the configured Stripe secret key
     * @return true when the configuration asks for the stand-in: {@link #LOCAL_TEST}, or {@link #AUTO} without a key
     * @throws IllegalArgumentException for an unknown mode
     */
    boolean wantsLocalTest(String mode, String secretKey);

    /**
     * @param activeProfiles Spring profiles in effect (the default profile when none is active)
     * @return true only for {@code local} without {@code dev}, {@code uat} or {@code prod}
     */
    boolean localTestAllowed(Set<String> activeProfiles);
}
