package com.afrotransact.identity.application;

/**
 * Local test accounts (S276): every {@link TestPersonas persona} with the shared password, verified email, roles,
 * organisation membership and saved address. Only where {@link TestUserSettings#enabled()}: the local profile, or dev
 * with an explicit flag; never uat or prod.
 */
public interface TestUserService {

    /**
     * Creates missing accounts and repairs drifted ones (password, verified email, enabled, phone, roles, membership,
     * address). Idempotent.
     *
     * @throws IllegalStateException when test accounts are not allowed in this environment
     */
    TestUserReport ensureAll();

    /**
     * Links the personas of an organisation that identity just mirrored from the seller service. A no-op where test
     * accounts are off.
     *
     * @return memberships created or changed
     */
    int linkOrganisation(String orgId);
}
