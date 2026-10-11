package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.RoleChanged;
import com.afrotransact.identity.application.IdentityEventPayloads.UserRegistered;
import com.afrotransact.identity.domain.LocalDevelopmentPolicy;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.AddressRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class TestUserServiceImpl implements TestUserService {

    static final String ACTOR = "local-test-users";
    private static final String REASON = "local test user (S276)";

    private final TestUserSettings settings;
    private final LocalDevelopmentPolicy policy;
    private final PasswordPolicy passwords;
    private final UserAccountRepository users;
    private final UserAccessRepository access;
    private final OrganizationRepository orgs;
    private final AddressRepository addresses;
    private final PasswordHasher hasher;
    private final MembershipWriter memberships;
    private final AccessChangeLog changeLog;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final List<TestPersona> personas;

    TestUserServiceImpl(TestUserSettings settings, LocalDevelopmentPolicy policy, PasswordPolicy passwords,
                        UserAccountRepository users, UserAccessRepository access, OrganizationRepository orgs,
                        AddressRepository addresses, PasswordHasher hasher, MembershipWriter memberships,
                        AccessChangeLog changeLog, DomainEventPublisher events, Clock clock) {
        this.settings = settings;
        this.policy = policy;
        this.passwords = passwords;
        this.users = users;
        this.access = access;
        this.orgs = orgs;
        this.addresses = addresses;
        this.hasher = hasher;
        this.memberships = memberships;
        this.changeLog = changeLog;
        this.events = events;
        this.clock = clock;
        this.personas = TestPersonas.ALL;
    }

    @Override
    @Transactional
    public TestUserReport ensureAll() {
        if (!settings.enabled()) {
            throw new IllegalStateException("Test users are off in this environment (local profile only, or dev "
                    + "with at.identity.local-development.test-users)");
        }
        Tally t = new Tally();
        for (TestPersona p : personas) {
            ensure(p, t);
        }
        return new TestUserReport(personas.size(), t.created, t.repaired, t.roles, t.linked, t.addresses, t.awaiting);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int linkOrganisation(String orgId) {
        if (!settings.enabled() || orgId == null) {
            return 0;
        }
        Optional<Organization> org = orgs.findById(orgId);
        if (org.isEmpty()) {
            return 0;
        }
        int linked = 0;
        for (TestPersona p : personas) {
            if (orgId.equals(p.orgId())) {
                Optional<UserAccount> user = users.findByEmail(UserAccount.normaliseEmail(p.email()));
                if (user.isPresent() && link(org.get(), user.get(), p.orgRole())) {
                    linked++;
                }
            }
        }
        return linked;
    }

    private void ensure(TestPersona p, Tally t) {
        String email = UserAccount.normaliseEmail(p.email());
        if (!policy.isTestUserEmail(email)) {
            throw new IllegalStateException("Test user " + email + " must use @"
                    + LocalDevelopmentPolicy.TEST_USER_DOMAIN);
        }
        List<String> weak = passwords.violations(settings.password(), List.of(p.firstName(), p.lastName(),
                email.substring(0, email.indexOf('@'))));
        if (!weak.isEmpty()) {
            throw new IllegalStateException("The test users' password does not meet the password policy for "
                    + email + ": " + String.join("; ", weak));
        }
        Instant now = Instant.now(clock);
        UserAccount user = users.findByEmail(email).map(u -> repaired(u, p, now, t))
                .orElseGet(() -> created(p, now, t));
        for (String role : p.roles()) {
            if (access.grantRole(user.id(), role, ACTOR)) {
                changeLog.append(ACTOR, "role.granted", user.id(), role, role, REASON);
                events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, user.id().toString(),
                        new RoleChanged(user.id().toString(), role, null, ACTOR, REASON, now));
                t.roles++;
            }
        }
        if (p.orgId() != null) {
            Optional<Organization> org = orgs.findById(p.orgId());
            if (org.isEmpty()) {
                t.awaiting.add(p.orgId());
            } else if (link(org.get(), user, p.orgRole())) {
                t.linked++;
            }
        }
        if (p.address() != null && addAddress(user, p, now)) {
            t.addresses++;
        }
    }

    private UserAccount created(TestPersona p, Instant now, Tally t) {
        UserAccount user = users.insert(new UserAccount(UUID.randomUUID(), p.firstName(), p.lastName(), p.email(),
                true, p.phone(), hasher.hash(settings.password()), true, false, now, null, now, now, 0));
        changeLog.append(ACTOR, "user.created", user.id(), null, user.email(), REASON);
        events.publish(IdentityEvents.USER_REGISTERED, IdentityEvents.AGGREGATE, user.id().toString(),
                new UserRegistered(user.id().toString(), user.email(), user.firstName(), user.lastName(),
                        user.phone(), intent(p), p.roles(), "ADMIN", true, false, now));
        t.created++;
        return user;
    }

    /** Puts back what a test session may have changed: password, verified email, enabled, phone. */
    private UserAccount repaired(UserAccount u, TestPersona p, Instant now, Tally t) {
        boolean passwordOk = u.hasPassword() && hasher.matches(settings.password(), u.passwordHash());
        String phone = p.phone() != null ? p.phone() : u.phone();
        if (passwordOk && u.emailVerified() && u.enabled() && Objects.equals(phone, u.phone())) {
            return u;
        }
        UserAccount fixed = users.update(new UserAccount(u.id(), u.firstName(), u.lastName(), u.email(), true, phone,
                passwordOk ? u.passwordHash() : hasher.hash(settings.password()), true, u.smsConsent(),
                u.termsAcceptedAt(), u.activeOrgId(), u.createdAt(), now, u.version()));
        changeLog.append(ACTOR, "user.repaired", u.id(), null, u.email(), REASON);
        t.repaired++;
        return fixed;
    }

    private boolean link(Organization org, UserAccount user, String role) {
        Optional<String> current = orgs.memberRole(org.id(), user.id());
        if (current.isPresent() && current.get().equals(role)) {
            return false;
        }
        return memberships.set(org, user, current.orElse(null), role, ACTOR, REASON);
    }

    private boolean addAddress(UserAccount user, TestPersona p, Instant now) {
        TestPersona.Address a = p.address();
        List<PostalAddress> book = addresses.findByUser(user.id());
        boolean present = book.stream().anyMatch(x -> a.line1().equalsIgnoreCase(x.line1())
                && a.postalCode().equals(x.postalCode()));
        if (present) {
            return false;
        }
        UUID id = UUID.randomUUID();
        addresses.insert(new PostalAddress(id, user.id(), a.label(), user.displayName(), p.phone(), a.line1(), null,
                a.city(), a.state(), a.postalCode(), "US", a.latitude(), a.longitude(), a.instructions(), true,
                false, now, now));
        if (book.stream().noneMatch(PostalAddress::isDefault)) {
            addresses.setDefault(user.id(), id);
        }
        return true;
    }

    private static String intent(TestPersona p) {
        if (p.roles().stream().anyMatch(r -> r.startsWith("platform."))) {
            return "STAFF";
        }
        if (p.orgRole() != null) {
            return p.orgRole().startsWith("provider.") ? "PROVIDE" : "SELL";
        }
        return "SHOP";
    }

    /** Counters of one run. */
    private static final class Tally {
        private int created;
        private int repaired;
        private int roles;
        private int linked;
        private int addresses;
        private final Set<String> awaiting = new LinkedHashSet<>();
    }
}
