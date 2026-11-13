package com.afrotransact.identity.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The difference between a client's configured and stored scopes and redirect URIs, and what a mode does about it.
 * Pure value logic: no Spring, no persistence.
 *
 * @param addedScopes             configured scopes the stored client lacks
 * @param removedScopes           stored scopes the configuration no longer lists
 * @param addedRedirectUris       configured redirect URIs the stored client lacks
 * @param removedRedirectUris     stored redirect URIs the configuration no longer lists
 * @param addedPostLogoutUris     configured post-logout redirect URIs the stored client lacks
 * @param removedPostLogoutUris   stored post-logout redirect URIs the configuration no longer lists
 */
public record ClientReconcilePlan(Set<String> addedScopes, Set<String> removedScopes, Set<String> addedRedirectUris,
                                  Set<String> removedRedirectUris, Set<String> addedPostLogoutUris,
                                  Set<String> removedPostLogoutUris) {

    /** Compares the configured sets with the stored ones. */
    public static ClientReconcilePlan between(Set<String> configuredScopes, Set<String> storedScopes,
                                              Set<String> configuredRedirects, Set<String> storedRedirects,
                                              Set<String> configuredPostLogout, Set<String> storedPostLogout) {
        return new ClientReconcilePlan(minus(configuredScopes, storedScopes), minus(storedScopes, configuredScopes),
                minus(configuredRedirects, storedRedirects), minus(storedRedirects, configuredRedirects),
                minus(configuredPostLogout, storedPostLogout), minus(storedPostLogout, configuredPostLogout));
    }

    /** True when the configuration and the stored client agree. */
    public boolean inSync() {
        return addedScopes.isEmpty() && removedScopes.isEmpty() && addedRedirectUris.isEmpty()
                && removedRedirectUris.isEmpty() && addedPostLogoutUris.isEmpty() && removedPostLogoutUris.isEmpty();
    }

    /** True when {@code mode} would change the stored client (REPORT never does; ADDITIVE only for additions). */
    public boolean changes(ClientReconcileMode mode) {
        return switch (mode) {
            case REPORT -> false;
            case ADDITIVE -> !addedScopes.isEmpty() || !addedRedirectUris.isEmpty() || !addedPostLogoutUris.isEmpty();
            case SYNC -> !inSync();
        };
    }

    /** The scopes the stored client should have after {@code mode} is applied. */
    public Set<String> resultingScopes(ClientReconcileMode mode, Set<String> stored) {
        return result(mode, stored, addedScopes, removedScopes);
    }

    /** The redirect URIs the stored client should have after {@code mode} is applied. */
    public Set<String> resultingRedirectUris(ClientReconcileMode mode, Set<String> stored) {
        return result(mode, stored, addedRedirectUris, removedRedirectUris);
    }

    /** The post-logout redirect URIs the stored client should have after {@code mode} is applied. */
    public Set<String> resultingPostLogoutUris(ClientReconcileMode mode, Set<String> stored) {
        return result(mode, stored, addedPostLogoutUris, removedPostLogoutUris);
    }

    private static Set<String> result(ClientReconcileMode mode, Set<String> stored, Set<String> added,
                                      Set<String> removed) {
        Set<String> out = new LinkedHashSet<>(stored);
        if (mode == ClientReconcileMode.REPORT) {
            return out;
        }
        out.addAll(added);
        if (mode == ClientReconcileMode.SYNC) {
            out.removeAll(removed);
        }
        return out;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
