package com.afrotransact.payments.port;

/**
 * Stripe refused or failed a call.
 *
 * @see #clientError() 4xx answers (card declined, invalid state) are not worth retrying
 */
public class StripeGatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String stripeCode;
    private final String declineCode;
    private final int httpStatus;

    public StripeGatewayException(String message, String stripeCode, String declineCode, int httpStatus,
                                  Throwable cause) {
        super(message, cause);
        this.stripeCode = stripeCode;
        this.declineCode = declineCode;
        this.httpStatus = httpStatus;
    }

    public String stripeCode() {
        return stripeCode;
    }

    public String declineCode() {
        return declineCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean clientError() {
        return httpStatus >= 400 && httpStatus < 500 && httpStatus != 429 && httpStatus != 409;
    }

    /**
     * Stripe understood the request and definitively said no (for example {@code balance_insufficient}): retrying the
     * same call gets the same answer. Authentication and permission errors (401, 403) are the platform's own
     * misconfiguration, not an answer about the object, and 5xx, 429, 409 and network failures are transient: none of
     * those is a refusal.
     */
    public boolean refusal() {
        return clientError() && httpStatus != 401 && httpStatus != 403;
    }
}
