package com.afrotransact.seller.adapter.out.stripe;

import com.afrotransact.seller.port.GatewayException;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

/**
 * Runs a Stripe call and maps failures: invalid requests and key/permission problems → rejected (422, with Stripe's
 * message, so a misconfigured key says so on screen), anything else → unavailable (502).
 */
final class StripeCalls {

    private StripeCalls() {
    }

    interface Call<T> {
        T run() throws StripeException;
    }

    /** Extra attempts, and the first wait (doubling), for a call that may fail for a moment (see {@link #callWithRetry}). */
    static final int TRANSIENT_RETRIES = 2;
    static final long TRANSIENT_BACKOFF_MS = 300;

    static <T> T call(String what, Call<T> call) {
        try {
            return call.run();
        } catch (StripeException e) {
            throw translate(what, e);
        }
    }

    /**
     * Like {@link #call} for an idempotent step, with a bounded retry of failures that pass by themselves: Stripe
     * unavailable (5xx), rate limited (429), a dropped connection, and "no such account" in the moment after the
     * account was created. Waits {@code backoffMs}, then twice that, between attempts; everything else fails at once.
     */
    static <T> T callWithRetry(String what, Call<T> call, int retries, long backoffMs) {
        for (int attempt = 0;; attempt++) {
            try {
                return call.run();
            } catch (StripeException e) {
                if (attempt >= retries || !passesByItself(e)) {
                    throw translate(what, e);
                }
                try {
                    Thread.sleep(backoffMs << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw translate(what, e);
                }
            }
        }
    }

    static boolean passesByItself(StripeException e) {
        if (e instanceof ApiConnectionException || e instanceof RateLimitException) {
            return true;
        }
        if (e instanceof AuthenticationException || e instanceof CardException) {
            return false;
        }
        Integer status = e.getStatusCode();
        if (e instanceof InvalidRequestException) {
            return "resource_missing".equals(e.getCode());
        }
        return status != null && status >= 500;
    }

    private static GatewayException translate(String what, StripeException e) {
        if (e instanceof InvalidRequestException || e instanceof CardException) {
            return new GatewayException("seller.stripe-rejected", "Stripe rejected " + what + ": " + e.getUserMessage(),
                    true, e);
        }
        if (e instanceof AuthenticationException) { // includes PermissionException
            return new GatewayException("seller.stripe-key", "Stripe refused the seller service's key (" + what
                    + "): " + e.getUserMessage() + " Check STRIPE_SECRET_KEY: a test secret key of the platform account"
                    + " with Connect enabled (restricted keys need Connect write access).", true, e);
        }
        return new GatewayException("seller.stripe-unavailable", "Stripe is unavailable (" + what + "); retry", false, e);
    }
}
