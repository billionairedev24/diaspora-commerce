package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgMember;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/* Identity's copy of seller organisations: events never roll a newer copy back, and the reconcile repairs a missing or
   PENDING organisation and its owner memberships from the seller service's state. */
class SellerOrgSyncServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
    private static final String ORG = "org_malobranding000001";

    private final InboxDeduplicator inbox = mock(InboxDeduplicator.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final List<Organization> stored = new ArrayList<>();
    private final List<OrgMember> members = new ArrayList<>();
    private final UUID applicant = UUID.randomUUID();
    private UserAccount account;

    private final SellerOrgSyncServiceImpl sync = new SellerOrgSyncServiceImpl(inbox, orgs, users,
            mock(AccessChangeLog.class), events, mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void repositories() {
        when(inbox.firstDelivery(anyString(), any())).thenReturn(true);
        when(orgs.findById(ORG)).thenAnswer(i -> stored.isEmpty() ? Optional.empty() : Optional.of(stored.getLast()));
        when(orgs.upsert(any(Organization.class))).thenAnswer(i -> {
            stored.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(orgs.members(ORG)).thenAnswer(i -> List.copyOf(members));
        when(orgs.setMember(eq(ORG), any(UUID.class), anyString())).thenAnswer(i -> {
            UUID user = i.getArgument(1);
            String role = i.getArgument(2);
            boolean changed = members.stream().noneMatch(m -> m.userId().equals(user) && m.roleKey().equals(role));
            members.removeIf(m -> m.userId().equals(user));
            members.add(new OrgMember(user, "Malo", "Ade", "malo@example.com", role, role, true, NOW));
            return changed;
        });
        account = new UserAccount(applicant, "Malo", "Ade", "malo@example.com", true, null, "h", true, false, NOW,
                null, NOW, NOW, 0);
        when(users.findById(applicant)).thenAnswer(i -> Optional.of(account));
        when(users.update(any(UserAccount.class))).thenAnswer(i -> {
            account = i.getArgument(0);
            return account;
        });
    }

    private SellerOrgChange change(UUID eventId, String status, Instant at) {
        return new SellerOrgChange(eventId, ORG, "SELLER", "SELLER", "Malo Branding LLC", "Malo Branding", "US", status,
                List.of(applicant.toString()), applicant.toString(), at);
    }

    @Test
    void reconcileCreatesTheMissingOrganisationAndTheOwnerMembershipOnce() {
        assertThat(sync.reconcile(change(null, "APPROVED", NOW))).isTrue();
        assertThat(stored.getLast().status()).isEqualTo(OrgStatus.APPROVED);
        assertThat(members).extracting(OrgMember::roleKey).containsExactly("seller.owner");
        assertThat(account.activeOrgId()).as("their next token carries the organisation").isEqualTo(ORG);
        verify(events).publish(eq(IdentityEvents.ROLE_GRANTED), anyString(), eq(applicant.toString()), any());
        verify(inbox, never()).firstDelivery(anyString(), any());

        assertThat(sync.reconcile(change(null, "APPROVED", NOW))).as("nothing left to repair").isFalse();
    }

    @Test
    void reconcileApprovesAPendingCopy() {
        stored.add(new Organization(ORG, OrgKind.SELLER, "Malo Branding", OrgStatus.PENDING, "US", NOW.minusSeconds(60)));
        assertThat(sync.reconcile(change(null, "APPROVED", NOW))).isTrue();
        assertThat(stored.getLast().status()).isEqualTo(OrgStatus.APPROVED);
        assertThat(members).hasSize(1);
    }

    @Test
    void reconcileKeepsAMemberWhoseRoleTheTeamChangedButRestoresOwners() {
        stored.add(new Organization(ORG, OrgKind.SELLER, "Malo Branding", OrgStatus.APPROVED, "US", NOW));
        members.add(new OrgMember(applicant, "Malo", "Ade", "malo@example.com", "seller.admin", "Admin", true, NOW));
        SellerOrgChange truth = new SellerOrgChange(null, ORG, "SELLER", "SELLER", "Malo Branding LLC", "Malo Branding",
                "US", "APPROVED", List.of(), applicant.toString(), NOW);
        assertThat(sync.reconcile(truth)).isFalse();
        assertThat(members).extracting(OrgMember::roleKey).containsExactly("seller.admin");
    }

    @Test
    void anOlderEventNeverRollsTheCopyBack() {
        assertThat(sync.approved(change(UUID.randomUUID(), "APPROVED", NOW))).isTrue();
        // A seller.org.updated from before the approval, delivered late.
        assertThat(sync.updated(change(UUID.randomUUID(), "PENDING_REVIEW", NOW.minusSeconds(30)))).isTrue();
        assertThat(stored).hasSize(1);
        assertThat(stored.getLast().status()).isEqualTo(OrgStatus.APPROVED);
    }

    @Test
    void aLateApprovalAfterANewerSuspensionStillAddsTheOwner() {
        assertThat(sync.updated(change(UUID.randomUUID(), "SUSPENDED", NOW))).isTrue();
        assertThat(sync.approved(change(UUID.randomUUID(), "APPROVED", NOW.minusSeconds(120)))).isTrue();
        assertThat(stored.getLast().status()).isEqualTo(OrgStatus.SUSPENDED);
        assertThat(members).extracting(OrgMember::roleKey).containsExactly("seller.owner");
    }

    @Test
    void reconcileLeavesUnapprovedOrganisationsWithoutMembers() {
        assertThat(sync.reconcile(change(null, "REJECTED", NOW))).isTrue();
        assertThat(members).isEmpty();
    }
}
