package com.afrotransact.identity.application;

import java.util.Set;

/**
 * What one provisioning run of the local test accounts did.
 *
 * @param awaitingOrgs organisations not mirrored yet (run make seed; members are linked when they arrive)
 */
public record TestUserReport(int accounts, int created, int repaired, int rolesGranted, int membershipsLinked,
                             int addressesAdded, Set<String> awaitingOrgs) {

    public TestUserReport {
        awaitingOrgs = Set.copyOf(awaitingOrgs);
    }
}
