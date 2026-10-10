package com.afrotransact.bookings.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.DayOverride;
import com.afrotransact.bookings.domain.TeamMember;

/** Persistence port for provider team members, their weekly hours and date overrides (time off, special hours). */
public interface TeamRepository {

    TeamMember insert(TeamMember member);

    TeamMember update(TeamMember member);

    Optional<TeamMember> find(UUID id);

    /** Locks the member row (SELECT … FOR UPDATE): serialises bookings of one member (daily limit, overlaps). */
    Optional<TeamMember> lock(UUID id);

    /** Members of an organisation in a stable order (owner first, then by name). */
    List<TeamMember> byOrg(String orgId);

    Optional<TeamMember> byUser(String orgId, String userId);

    List<DayOverride> overrides(UUID memberId, LocalDate from, LocalDate to);

    void putOverride(UUID memberId, DayOverride override);

    boolean deleteOverride(UUID memberId, LocalDate date);
}
