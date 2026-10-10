package com.afrotransact.trustops.port;

/** A call to another AfroTransact service failed (status 0 = unreachable / timed out). */
public class UpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String service;
    private final int status;
    private final String code;

    public UpstreamException(String service, int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
        this.status = status;
        this.code = code;
    }

    public String service() {
        return service;
    }

    public int status() {
        return status;
    }

    /** The upstream problem {@code code}, when it returned one. */
    public String code() {
        return code;
    }

    /** The upstream rejected the request because of the target's state (409 / 422), not because it is down. */
    public boolean rejected() {
        return status == 409 || status == 422;
    }
}
