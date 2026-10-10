package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.Organisation;

/** Organisations and their members. */
public interface OrganisationRepository {

    Organisation insert(Organisation org);

    /** Optimistic update on {@code version}; throws a conflict when someone else changed it. */
    Organisation update(Organisation org);

    Optional<Organisation> findById(String id);

    /** Reads and row-locks the organisation for the rest of the transaction (serialises suspend / reinstate). */
    Optional<Organisation> lock(String id);

    void addMember(OrgMember member);

    /**
     * Sets the member's role from identity (S236) unless the stored row is newer or already has that role.
     *
     * @return true when a row was created or its role changed
     */
    boolean syncMember(OrgMember member);

    /**
     * Removes the member when it still holds {@code roleKey} and was not changed after {@code at}.
     *
     * @return true when a row was deleted
     */
    boolean removeMember(String orgId, String userId, String roleKey, Instant at);

    Optional<OrgMember> findMember(String orgId, String userId);

    List<OrgMember> members(String orgId);
}
