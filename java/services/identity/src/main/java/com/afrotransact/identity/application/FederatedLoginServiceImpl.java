package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.UserRegistered;
import com.afrotransact.identity.domain.FederatedIdentity;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.LandingPolicy;
import com.afrotransact.identity.domain.RoleKeys;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.FederatedIdentityRepository;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FederatedLoginServiceImpl implements FederatedLoginService {

    private final IdentityProviderService providers;
    private final FederatedIdentityRepository federated;
    private final UserAccountRepository users;
    private final UserAccessRepository access;
    private final SignUpPolicy signUpPolicy;
    private final LandingPolicy landing;
    private final AccessChangeLog changeLog;
    private final DomainEventPublisher events;
    private final Clock clock;

    FederatedLoginServiceImpl(IdentityProviderService providers, FederatedIdentityRepository federated,
                              UserAccountRepository users, UserAccessRepository access, SignUpPolicy signUpPolicy,
                              LandingPolicy landing, AccessChangeLog changeLog, DomainEventPublisher events,
                              Clock clock) {
        this.providers = providers;
        this.federated = federated;
        this.users = users;
        this.access = access;
        this.signUpPolicy = signUpPolicy;
        this.landing = landing;
        this.changeLog = changeLog;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID signIn(FederatedProfile p, Optional<UUID> signedInUser) {
        if (!providers.isEnabled(p.provider())) {
            throw new ForbiddenActionException("identity.provider-disabled", p.provider() + " sign-in is turned off");
        }
        Instant now = Instant.now(clock);
        Optional<FederatedIdentity> linked = federated.find(p.provider(), p.subject());
        if (linked.isPresent()) {
            UUID owner = linked.get().userId();
            if (signedInUser.isPresent() && !signedInUser.get().equals(owner)) {
                throw new ConflictException("identity.provider-linked-elsewhere",
                        "That " + p.provider() + " account is linked to a different AfroTransact account");
            }
            return requireEnabled(owner);
        }
        if (signedInUser.isPresent()) {
            link(p, signedInUser.get(), now, "linked from Login & security");
            return requireEnabled(signedInUser.get());
        }
        String email = UserAccount.normaliseEmail(p.email());
        if (email == null || !p.emailVerified()) {
            throw new IdentityRuleViolation("identity.provider-email-unverified",
                    p.provider() + " did not share a verified email address");
        }
        Optional<UserAccount> byEmail = users.findByEmail(email);
        if (byEmail.isPresent()) {
            UserAccount existing = byEmail.get();
            if (!existing.emailVerified()) {
                users.update(existing.verified(now));
            }
            link(p, existing.id(), now, "matched verified email");
            return requireEnabled(existing.id());
        }
        if (landing.isStaffEmail(email)) {
            throw new ForbiddenActionException("identity.staff-email",
                    "Staff accounts are created by an access administrator");
        }
        UserAccount created = users.insert(new UserAccount(UUID.randomUUID(), name(p.givenName(), email),
                name(p.familyName(), ""), email, true, null, null, true, false, null, null, now, now, 0));
        access.grantRole(created.id(), RoleKeys.CUSTOMER, AccountServiceImpl.SELF);
        changeLog.append(AccountServiceImpl.SELF, "role.granted", created.id(), null, RoleKeys.CUSTOMER,
                "social sign-up via " + p.provider());
        link(p, created.id(), now, "social sign-up");
        events.publish(IdentityEvents.USER_REGISTERED, IdentityEvents.AGGREGATE, created.id().toString(),
                new UserRegistered(created.id().toString(), email, created.firstName(), created.lastName(), null,
                        "SHOP", List.of(RoleKeys.CUSTOMER), p.provider().toUpperCase(Locale.ROOT), true,
                        false, now));
        return created.id();
    }

    private void link(FederatedProfile p, UUID userId, Instant now, String why) {
        federated.link(new FederatedIdentity(p.provider(), p.subject(), userId, UserAccount.normaliseEmail(p.email()),
                now));
        changeLog.append(userId.toString(), "identity.linked", userId, null, p.provider(), why);
    }

    private UUID requireEnabled(UUID userId) {
        boolean enabled = users.findById(userId).map(UserAccount::enabled).orElse(false);
        if (!enabled) {
            throw new ForbiddenActionException("identity.account-disabled", "This account is disabled");
        }
        return userId;
    }

    /** Uses the provider name when it is a valid name; falls back so the profile page can ask for it. */
    private String name(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        if (signUpPolicy.validName(trimmed)) {
            return trimmed;
        }
        String local = fallback.contains("@") ? fallback.substring(0, fallback.indexOf('@')) : fallback;
        return local.isEmpty() ? "Customer" : local;
    }
}
