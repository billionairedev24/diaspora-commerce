package com.afrotransact.seller.port;

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

    void addMember(OrgMember member);

    Optional<OrgMember> findMember(String orgId, String userId);

    List<OrgMember> members(String orgId);
}
