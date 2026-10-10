package com.afrotransact.bookings.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.domain.TeamRole;
import com.afrotransact.bookings.domain.WeeklyHours;

/** Provider team as bookings sees it: members, roles, who takes appointments. */
public interface TeamService {

    /** Every member of the organisation (bookings:read). */
    List<TeamMember> list(String orgId);

    /** bookings:team:manage (Owner, Manager). */
    TeamMember add(String orgId, MemberCommand command);

    /** bookings:team:manage (Owner, Manager). */
    TeamMember update(String orgId, UUID memberId, MemberCommand command);

    /**
     * A member.
     *
     * @param userId   identity user id of the member's login (links the practitioner to their own calendar)
     * @param bookable null = default for the role (owners and practitioners take appointments)
     * @param hours    initial weekly hours (add only)
     */
    record MemberCommand(String userId, String displayName, TeamRole role, Boolean bookable, Boolean active,
                         WeeklyHours hours) {
    }
}
