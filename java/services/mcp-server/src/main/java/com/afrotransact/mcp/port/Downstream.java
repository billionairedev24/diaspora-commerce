package com.afrotransact.mcp.port;

/**
 * Result of a call to a platform API: a JSON tree (maps, lists, scalars) and how the call went. Tools turn
 * {@link Status#UNAVAILABLE} into "not available yet" instead of failing, so services that are not deployed yet degrade
 * gracefully.
 *
 * @param status outcome
 * @param body   response body as a JSON tree (null unless {@link Status#OK})
 */
public record Downstream(Status status, Object body) {

    public static Downstream ok(Object body) {
        return new Downstream(Status.OK, body);
    }

    public static Downstream of(Status status) {
        return new Downstream(status, null);
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** Outcome of a downstream call. */
    public enum Status {
        OK,
        NOT_FOUND,
        FORBIDDEN,
        /** 4xx validation error from the owning service. */
        REJECTED,
        /** Not deployed, timed out or 5xx. */
        UNAVAILABLE
    }
}
