package com.afrotransact.bookings.port;

/** payments-service refused a request (4xx) or could not be reached (5xx, timeout). */
public class PaymentsClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public PaymentsClientException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** HTTP status from payments, or 0 when it did not answer. */
    public int status() {
        return status;
    }

    /** payments' problem code (e.g. {@code payments.card-declined}), or null. */
    public String code() {
        return code;
    }

    public boolean rejected() {
        return status >= 400 && status < 500;
    }
}
