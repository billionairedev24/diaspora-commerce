package com.afrotransact.seller.domain;

import java.util.Set;

/**
 * When the seller service may run without Stripe. Locally there is usually no Stripe test key, so the {@code local}
 * profile can use a stand-in for Stripe Connect and Stripe Billing: verification is completed by the applicant on a
 * clearly labelled local screen in Seller Center, payout bank accounts and plan subscriptions are accepted without
 * reaching Stripe, and nothing leaves the machine. Every other environment talks to Stripe: {@code dev}, {@code uat}
 * and {@code prod} never get the stand-in, whatever the configuration says.
 */
public interface LocalTestStripePolicy {

    /** {@code at.seller.stripe.mode}: always Stripe (the default everywhere). */
    String STRIPE = "stripe";

    /** {@code at.seller.stripe.mode}: always the local stand-in. */
    String LOCAL_TEST = "local-test";

    /** {@code at.seller.stripe.mode}: the stand-in when no Stripe secret key is configured (the local default). */
    String AUTO = "auto";

    /**
     * @param mode      {@link #STRIPE}, {@link #LOCAL_TEST} or {@link #AUTO} (blank = {@link #STRIPE})
     * @param secretKey the configured Stripe secret key
     * @return true when the configuration asks for the stand-in
     * @throws IllegalArgumentException for an unknown mode
     */
    boolean wantsLocalTest(String mode, String secretKey);

    /**
     * @param activeProfiles Spring profiles in effect
     * @return true only for {@code local} without {@code dev}, {@code uat} or {@code prod}
     */
    boolean localTestAllowed(Set<String> activeProfiles);
}
