package com.afrotransact.seller.adapter.out.stripe;

import com.afrotransact.seller.port.GatewayException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
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

    static <T> T call(String what, Call<T> call) {
        try {
            return call.run();
        } catch (InvalidRequestException | CardException e) {
            throw new GatewayException("seller.stripe-rejected", "Stripe rejected " + what + ": " + e.getUserMessage(),
                    true, e);
        } catch (AuthenticationException e) { // includes PermissionException
            throw new GatewayException("seller.stripe-key", "Stripe refused the seller service's key (" + what
                    + "): " + e.getUserMessage() + " Check STRIPE_SECRET_KEY: a test secret key of the platform account"
                    + " with Connect enabled (restricted keys need Connect write access).", true, e);
        } catch (StripeException e) {
            throw new GatewayException("seller.stripe-unavailable", "Stripe is unavailable (" + what + "); retry",
                    false, e);
        }
    }
}
