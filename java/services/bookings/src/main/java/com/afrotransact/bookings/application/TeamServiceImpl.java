package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TeamServiceImpl implements TeamService {

    private final TeamRepository team;
    private final ProviderRepository providers;
    private final BookingsAccess access;
    private final Clock clock;

    TeamServiceImpl(TeamRepository team, ProviderRepository providers, BookingsAccess access, Clock clock) {
        this.team = team;
        this.providers = providers;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TeamMember> list(String orgId) {
        access.provider(orgId, BookingsPermissions.READ);
        return team.byOrg(orgId);
    }

    @Override
    @Transactional
    public TeamMember add(String orgId, MemberCommand c) {
        access.providerWide(orgId, BookingsPermissions.TEAM_MANAGE);
        providers.find(orgId).orElseThrow(() -> ProviderSetupServiceImpl.notFound(orgId));
        ensureUserFree(orgId, c.userId(), null);
        return team.insert(TeamMember.add(orgId, c.userId(), c.displayName(), c.role(), c.bookable(), c.hours(),
                Instant.now(clock)));
    }

    @Override
    @Transactional
    public TeamMember update(String orgId, UUID memberId, MemberCommand c) {
        access.providerWide(orgId, BookingsPermissions.TEAM_MANAGE);
        TeamMember current = team.lock(memberId).filter(m -> m.orgId().equals(orgId))
                .orElseThrow(() -> new NotFoundException("bookings.member-not-found",
                        "Team member " + memberId + " not found"));
        ensureUserFree(orgId, c.userId(), memberId);
        boolean bookable = c.bookable() == null ? current.bookable() : c.bookable();
        boolean active = c.active() == null || c.active();
        return team.update(current.update(c.userId(), c.displayName(), c.role(), bookable, active,
                Instant.now(clock)));
    }

    private void ensureUserFree(String orgId, String userId, UUID self) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        team.byUser(orgId, userId.trim()).filter(m -> !m.id().equals(self)).ifPresent(m -> {
            throw new ConflictException("bookings.user-already-member", "That user is already on the team as "
                    + m.displayName());
        });
    }
}
