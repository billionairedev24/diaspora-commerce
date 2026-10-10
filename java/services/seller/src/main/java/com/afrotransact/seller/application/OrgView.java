package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.Organisation;

/** Organisation with its members (members and staff). */
public record OrgView(Organisation organisation, List<OrgMember> members) {

    public OrgView {
        members = List.copyOf(members);
    }
}
