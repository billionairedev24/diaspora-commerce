package com.afrotransact.seller.adapter.out.stripe;

import com.afrotransact.seller.port.GatewayException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;

/** Runs a Stripe call and maps failures: invalid requests → rejected (422), anything else → unavailable (502). */
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
        } catch (StripeException e) {
            throw new GatewayException("seller.stripe-unavailable", "Stripe is unavailable (" + what + "); retry",
                    false, e);
        }
    }
}
