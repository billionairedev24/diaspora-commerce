package com.afrotransact.fulfilment.application;

import java.time.Instant;

import com.afrotransact.platform.web.error.ForbiddenActionException;

/**
 * The caller may not use the driver app: never granted ({@code fulfilment.no-driver-access}) or revoked
 * ({@code fulfilment.driver-access-revoked}, {@code signOut} true).
 */
public class DriverAccessDenied extends ForbiddenActionException {

    private static final long serialVersionUID = 1L;

    /** Shown on the "No driver access" screen. */
    public static final String GRANT_HINT = "A developer or super admin can grant delivery:operate in Backoffice → "
            + "Access control, either by giving you the Driver role or as a direct permission.";

    private final boolean signOut;
    private final transient Instant revokedAt;

    public DriverAccessDenied(String code, String message, boolean signOut, Instant revokedAt) {
        super(code, message);
        this.signOut = signOut;
        this.revokedAt = revokedAt;
    }

    public boolean signOut() {
        return signOut;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
