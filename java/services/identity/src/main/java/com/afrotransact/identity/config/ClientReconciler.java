package com.afrotransact.identity.config;

import com.afrotransact.identity.domain.ClientReconcileMode;

/**
 * Brings an already stored OAuth client in line with its configuration (audit F15): scopes, redirect URIs and
 * post-logout redirect URIs only. Everything else about the client (secret, grant types, token settings, consent,
 * client name) is left as the staff console has it, and the secret is only touched when rotation is asked for.
 */
interface ClientReconciler {

    /** What happened to one client. */
    record Outcome(boolean drift, boolean updated, boolean secretRotated) {

        static Outcome none() {
            return new Outcome(false, false, false);
        }
    }

    /**
     * Compares the configured client with the stored one and applies {@code mode}; every difference found and every
     * change made is logged as an audit line ({@code audit=true}, event {@code identity.client.drift} or
     * {@code identity.client.reconciled}). Idempotent: a second run finds nothing to do.
     */
    Outcome reconcile(IdentityProperties.Client configured, ClientReconcileMode mode, boolean rotateSecret);
}
