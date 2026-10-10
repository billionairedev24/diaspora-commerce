package com.afrotransact.payments.port;

import java.time.Instant;

/** Verifies the {@code Stripe-Signature} header of a webhook delivery against the endpoint secret. */
public interface StripeWebhookVerifier {

    /** @throws InvalidWebhookSignatureException when the signature, timestamp or payload is not valid */
    VerifiedEvent verify(String payload, String signatureHeader);

    /**
     * A signature-verified Stripe event.
     *
     * @param account connected account id for Connect events (payout.*), else null
     * @param payload raw JSON as delivered
     */
    record VerifiedEvent(String id, String type, String account, Instant created, boolean livemode, String payload) {
    }

    /** The delivery is not from Stripe (or was tampered with / replayed too late). */
    class InvalidWebhookSignatureException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public InvalidWebhookSignatureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
