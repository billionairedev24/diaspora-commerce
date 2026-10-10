package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Keeps {@code org_member} in step with identity-service, the source of truth for organisation members (S236):
 * {@code identity.role.granted} with an {@code orgId} adds the member or changes the role, {@code identity.role.revoked}
 * removes it while the member still holds that role. Events are de-duplicated on event id; organisations seller does
 * not know (for example AfroTransact Retail) and non-organisation roles are ignored.
 */
public interface OrgMemberSyncService {

    /** @return true when the member list changed */
    boolean granted(RoleChange change);

    /** @return true when the member list changed */
    boolean revoked(RoleChange change);

    /**
     * One identity role event.
     *
     * @param eventId event-id header (inbox key)
     * @param at      when identity applied the change (orders updates of one member)
     */
    record RoleChange(UUID eventId, String userId, String roleKey, String orgId, String actorId, Instant at) {
    }
}
