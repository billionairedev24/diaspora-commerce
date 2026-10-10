package com.afrotransact.taxreturns.port;

/** An upstream service call failed. {@link #retryable()} is false for refusals (4xx other than 408 / 429). */
public class UpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String upstream;
    private final int status;
    private final String code;

    public UpstreamException(String upstream, int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.upstream = upstream;
        this.status = status;
        this.code = code;
    }

    public String upstream() {
        return upstream;
    }

    /** HTTP status, 0 for I/O failures. */
    public int status() {
        return status;
    }

    /** RFC 9457 problem code of the upstream, if any. */
    public String code() {
        return code;
    }

    public boolean retryable() {
        return status == 0 || status == 408 || status == 429 || status >= 500;
    }
}
