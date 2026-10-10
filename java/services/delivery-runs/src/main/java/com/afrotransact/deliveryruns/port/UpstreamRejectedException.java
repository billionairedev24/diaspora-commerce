package com.afrotransact.deliveryruns.port;

/** An upstream service refused the request with a problem (its code is passed on; HTTP 422 to callers). */
public class UpstreamRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final int upstreamStatus;

    public UpstreamRejectedException(String code, String message, int upstreamStatus) {
        super(message);
        this.code = code;
        this.upstreamStatus = upstreamStatus;
    }

    /** The upstream problem code, e.g. {@code orders.payment-failed}. */
    public String code() {
        return code;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }
}
