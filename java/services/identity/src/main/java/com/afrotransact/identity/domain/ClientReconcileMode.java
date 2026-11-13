package com.afrotransact.identity.domain;

import java.util.Locale;

/**
 * What start-up does about differences between the OAuth clients configured in YAML and the clients already stored
 * (audit F15). The staff console owns a client once it exists (S291), so the default only reports.
 */
public enum ClientReconcileMode {

    /** Log the differences (audited as drift), change nothing. The default. */
    REPORT,

    /** Add the scopes and redirect URIs the YAML lists and the stored client lacks; never remove anything. */
    ADDITIVE,

    /** Make the stored scopes and redirect URIs equal to the YAML (removes what the YAML no longer lists). */
    SYNC;

    /** Parses a configured value; blank means {@link #REPORT}, anything unknown is a start-up error. */
    public static ClientReconcileMode parse(String value) {
        if (value == null || value.isBlank()) {
            return REPORT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("at.identity.provisioning.reconcile must be report, additive or sync (got '"
                    + value + "')", ex);
        }
    }
}
