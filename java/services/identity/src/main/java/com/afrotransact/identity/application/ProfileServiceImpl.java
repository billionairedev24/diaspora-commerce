package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.RoleChanged;
import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.AccessProfile;
import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.LandingPolicy;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.PhoneCheck;
import com.afrotransact.identity.domain.PhoneNumberPolicy;
import com.afrotransact.identity.domain.RoleKeys;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProfileServiceImpl implements ProfileService {

    private final CurrentUser current;
    private final UserAccountRepository users;
    private final UserAccessRepository access;
    private final AccessProfileLoader profiles;
    private final AccessPolicy accessPolicy;
    private final LandingPolicy landingPolicy;
    private final SignUpPolicy signUpPolicy;
    private final PhoneNumberPolicy phones;
    private final AccessChangeLog changeLog;
    private final DomainEventPublisher events;
    private final Clock clock;

    ProfileServiceImpl(CurrentUser current, UserAccountRepository users, UserAccessRepository access,
                       AccessProfileLoader profiles, AccessPolicy accessPolicy, LandingPolicy landingPolicy,
                       SignUpPolicy signUpPolicy, PhoneNumberPolicy phones, AccessChangeLog changeLog,
                       DomainEventPublisher events, Clock clock) {
        this.current = current;
        this.users = users;
        this.access = access;
        this.profiles = profiles;
        this.accessPolicy = accessPolicy;
        this.landingPolicy = landingPolicy;
        this.signUpPolicy = signUpPolicy;
        this.phones = phones;
        this.changeLog = changeLog;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public MeView me() {
        return view(user(current.id()));
    }

    @Override
    @Transactional
    public MeView update(UpdateProfileCommand c) {
        UserAccount u = user(current.id());
        Map<String, String> errors = new LinkedHashMap<>();
        String first = c.firstName() == null ? u.firstName() : c.firstName().trim();
        String last = c.lastName() == null ? u.lastName() : c.lastName().trim();
        if (!signUpPolicy.validName(first)) {
            errors.put("firstName", "Use letters, spaces, hyphens or apostrophes");
        }
        if (!signUpPolicy.validName(last)) {
            errors.put("lastName", "Use letters, spaces, hyphens or apostrophes");
        }
        String phone = u.phone();
        if (c.phone() != null) {
            PhoneCheck check = phones.check(c.phone(), c.phoneRegion());
            if (check.valid()) {
                phone = check.e164();
            } else {
                errors.put("phone", check.error());
            }
        }
        if (Boolean.FALSE.equals(c.termsAccepted()) && u.termsAcceptedAt() == null) {
            errors.put("termsAccepted", "Accept the Conditions of Use and Privacy Notice to continue");
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-profile", "Check the highlighted fields", errors);
        }
        Instant now = Instant.now(clock);
        Instant terms = u.termsAcceptedAt() == null && Boolean.TRUE.equals(c.termsAccepted()) ? now
                : u.termsAcceptedAt();
        boolean sms = c.smsConsent() == null ? u.smsConsent() : c.smsConsent();
        UserAccount updated = users.update(new UserAccount(u.id(), first, last, u.email(), u.emailVerified(), phone,
                u.passwordHash(), u.enabled(), sms, terms, u.activeOrgId(), u.createdAt(), now, u.version()));
        return view(updated);
    }

    @Override
    @Transactional
    public MeView becomeApplicant(OrgKind kind) {
        UserAccount u = user(current.id());
        String role = kind == OrgKind.SELLER ? RoleKeys.SELLER_APPLICANT : RoleKeys.PROVIDER_APPLICANT;
        if (access.grantRole(u.id(), role, u.id().toString())) {
            Instant now = Instant.now(clock);
            changeLog.append(u.id().toString(), "role.granted", u.id(), null, role, "self-service: start "
                    + (kind == OrgKind.SELLER ? "selling" : "offering services"));
            events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, u.id().toString(),
                    new RoleChanged(u.id().toString(), role, null, u.id().toString(), "self-service", now));
        }
        return view(u);
    }

    @Override
    @Transactional
    public MeView selectOrg(String orgId) {
        UserAccount u = user(current.id());
        AccessProfile profile = profiles.load(u);
        boolean member = profile.memberships().stream().anyMatch(m -> m.orgId().equals(orgId));
        if (!member) {
            throw new NotFoundException("identity.org-not-found", "You are not a member of that organisation");
        }
        return view(users.update(u.withActiveOrg(orgId, Instant.now(clock))));
    }

    private UserAccount user(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }

    private MeView view(UserAccount u) {
        AccessProfile profile = profiles.load(u);
        EffectiveAccess resolved = accessPolicy.resolve(profile);
        boolean complete = u.phone() != null && u.termsAcceptedAt() != null;
        return new MeView(u, resolved, profile.memberships(), landingPolicy.landing(profile, resolved), complete);
    }
}
