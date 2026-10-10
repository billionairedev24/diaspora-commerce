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
}
