package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.identity.domain.DefaultLocalDevelopmentPolicy;
import com.afrotransact.identity.domain.DefaultPasswordPolicy;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.AddressRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestUserServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-11T08:00:00Z");
    private static final String PASSWORD = "Test1234!Local";

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final UserAccessRepository access = mock(UserAccessRepository.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final AddressRepository addresses = mock(AddressRepository.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final PasswordHasher hasher = new PasswordHasher() {
        @Override
        public String hash(String raw) {
            return "h:" + raw;
        }

        @Override
        public boolean matches(String raw, String hash) {
            return ("h:" + raw).equals(hash);
        }
    };

    /** In-memory accounts, roles, memberships and addresses behind the mocks. */
    private final Map<String, UserAccount> byEmail = new HashMap<>();
    private final Map<String, Set<String>> roles = new HashMap<>();
    private final Map<String, String> members = new HashMap<>();
    private final List<PostalAddress> book = new ArrayList<>();

    private TestUserServiceImpl service(boolean enabled) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MembershipWriter writer = new MembershipWriter(orgs, users, changeLog, events, clock);
        return new TestUserServiceImpl(enabled ? new TestUserSettings(true, PASSWORD) : TestUserSettings.off(),
                new DefaultLocalDevelopmentPolicy(), new DefaultPasswordPolicy(), users, access, orgs, addresses,
                hasher, writer, changeLog, events, clock);
    }

    @BeforeEach
    void store() {
        when(users.findByEmail(anyString())).thenAnswer(i -> Optional.ofNullable(byEmail.get(i.<String>getArgument(0))));
        when(users.findById(any())).thenAnswer(i -> byEmail.values().stream()
                .filter(u -> u.id().equals(i.getArgument(0))).findFirst());
        when(users.insert(any())).thenAnswer(i -> {
            UserAccount u = i.getArgument(0);
            byEmail.put(u.email(), u);
            return u;
        });
        when(users.update(any())).thenAnswer(i -> {
            UserAccount u = i.getArgument(0);
            byEmail.put(u.email(), u);
            return u;
        });
        when(access.grantRole(any(), anyString(), anyString())).thenAnswer(i -> roles
                .computeIfAbsent(i.getArgument(0).toString(), k -> new HashSet<>()).add(i.getArgument(1)));
        when(orgs.findById(anyString())).thenAnswer(i -> {
            String id = i.getArgument(0);
            return "org_afrotransact_retail".equals(id) || "org_mama_put_pantry".equals(id)
                    ? Optional.of(new Organization(id, OrgKind.SELLER, id, OrgStatus.APPROVED, "US", NOW))
                    : Optional.empty();
        });
        when(orgs.memberRole(anyString(), any())).thenAnswer(i -> Optional.ofNullable(
                members.get(i.getArgument(0) + "/" + i.getArgument(1))));
        when(orgs.setMember(anyString(), any(), anyString())).thenAnswer(i -> {
            String prev = members.put(i.getArgument(0) + "/" + i.getArgument(1), i.getArgument(2));
            return !i.getArgument(2).equals(prev);
        });
        when(addresses.findByUser(any())).thenAnswer(i -> book.stream()
                .filter(a -> a.userId().equals(i.getArgument(0))).toList());
        doAnswer(i -> book.add(i.getArgument(0))).when(addresses).insert(any());
    }

    @Test
    void createsEveryPersonaVerifiedWithTheSharedPasswordRolesMembershipsAndAddresses() {
        TestUserReport r = service(true).ensureAll();

        assertThat(r.accounts()).isEqualTo(TestPersonas.ALL.size());
        assertThat(r.created()).isEqualTo(TestPersonas.ALL.size());
        assertThat(byEmail).hasSize(TestPersonas.ALL.size());
        assertThat(byEmail.keySet()).allMatch(e -> e.endsWith("@afrotransact.test"));
        assertThat(byEmail.values()).allMatch(u -> u.emailVerified() && u.enabled() && hasher.matches(PASSWORD,
                u.passwordHash()));
        UserAccount support = byEmail.get("staff.support@afrotransact.test");
        assertThat(roles.get(support.id().toString())).containsExactly("platform.customer_support");
        assertThat(support.phone()).isEqualTo("+17135550105");
        UserAccount owner = byEmail.get("seller.owner@afrotransact.test");
        assertThat(members).containsEntry("org_mama_put_pantry/" + owner.id(), "seller.owner");
        assertThat(members).containsEntry("org_mama_put_pantry/" + byEmail.get("seller.staff@afrotransact.test").id(),
                "seller.catalog");
        assertThat(members).containsEntry("org_afrotransact_retail/"
                + byEmail.get("retail.operator@afrotransact.test").id(), "seller.admin");
        assertThat(r.awaitingOrgs()).containsExactly("org_aminatas_braids");
        assertThat(r.membershipsLinked()).isEqualTo(3);
        assertThat(book).extracting(PostalAddress::postalCode).containsExactlyInAnyOrder("77042", "79401");
        assertThat(book).allMatch(PostalAddress::verified);
        verify(addresses).setDefault(eq(byEmail.get("customer.houston@afrotransact.test").id()), any());
        verify(events, times(TestPersonas.ALL.size()))
                .publish(eq(IdentityEvents.USER_REGISTERED), anyString(), anyString(), any());
    }

    @Test
    void secondRunChangesNothingAndRepairsAChangedPassword() {
        TestUserServiceImpl service = service(true);
        service.ensureAll();
        UserAccount houston = byEmail.get("customer.houston@afrotransact.test");
        byEmail.put(houston.email(), houston.withPassword("h:Something-Else-2026", NOW).withEnabled(false, NOW));

        TestUserReport again = service.ensureAll();

        assertThat(again.created()).isZero();
        assertThat(again.repaired()).isEqualTo(1);
        assertThat(again.rolesGranted()).isZero();
        assertThat(again.membershipsLinked()).isZero();
        assertThat(again.addressesAdded()).isZero();
        UserAccount fixed = byEmail.get(houston.email());
        assertThat(fixed.enabled()).isTrue();
        assertThat(hasher.matches(PASSWORD, fixed.passwordHash())).isTrue();
        assertThat(book).hasSize(2);
    }

    @Test
    void linksAnOrganisationWhenItIsMirroredLater() {
        TestUserServiceImpl service = service(true);
        service.ensureAll();
        UserAccount provider = byEmail.get("provider.owner@afrotransact.test");
        when(orgs.findById("org_aminatas_braids")).thenReturn(Optional.of(new Organization("org_aminatas_braids",
                OrgKind.PROVIDER, "Aminata's Braids", OrgStatus.APPROVED, "US", NOW)));

        assertThat(service.linkOrganisation("org_aminatas_braids")).isEqualTo(1);
        assertThat(members).containsEntry("org_aminatas_braids/" + provider.id(), "provider.owner");
        assertThat(service.linkOrganisation("org_aminatas_braids")).isZero();
        assertThat(service.linkOrganisation("org_somebody_else")).isZero();
    }

    @Test
    void refusesWhereTestUsersAreOff() {
        assertThatThrownBy(() -> service(false).ensureAll()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("off in this environment");
        assertThat(service(false).linkOrganisation("org_mama_put_pantry")).isZero();
        verify(users, never()).insert(any());
        verifyNoInteractions(access, addresses, events);
    }

    @Test
    void refusesAPasswordThePolicyRejects() {
        TestUserServiceImpl weak = new TestUserServiceImpl(new TestUserSettings(true, "short"),
                new DefaultLocalDevelopmentPolicy(), new DefaultPasswordPolicy(), users, access, orgs, addresses,
                hasher, new MembershipWriter(orgs, users, changeLog, events, Clock.systemUTC()), changeLog, events,
                Clock.systemUTC());
        assertThatThrownBy(weak::ensureAll).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password policy");
    }

    @Test
    void everyPersonaUsesTheTestDomainAndEveryStaffPersonaHasAPhoneForItsTextedCode() {
        assertThat(TestPersonas.ALL).extracting(TestPersona::email).doesNotHaveDuplicates()
                .allMatch(e -> e.endsWith("@afrotransact.test"));
        assertThat(TestPersonas.ALL).filteredOn(p -> TestPersonas.STAFF_CONSOLE.equals(p.app()))
                .allMatch(p -> p.phone() != null && p.roles().stream().allMatch(r -> r.startsWith("platform.")))
                .hasSize(14);
    }
}
