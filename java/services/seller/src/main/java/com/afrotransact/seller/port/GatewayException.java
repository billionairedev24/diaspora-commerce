package com.afrotransact.seller.port;

/**
 * An external system (Stripe, media-service) failed or rejected a call.
 *
 * @see #rejected() true when the request was invalid (HTTP 422), false when the system was unavailable (HTTP 502)
 */
public class GatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean rejected;

    public GatewayException(String code, String message, boolean rejected, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.rejected = rejected;
    }

    public String code() {
        return code;
    }

    public boolean rejected() {
        return rejected;
    }
}
