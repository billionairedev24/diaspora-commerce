package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.UserEmailVerified;
import com.afrotransact.identity.application.IdentityEventPayloads.UserRegistered;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.LandingPolicy;
import com.afrotransact.identity.domain.OrgInvitation;
import com.afrotransact.identity.domain.SignUpDetails;
import com.afrotransact.identity.domain.SignUpIntent;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.ValidatedSignUp;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountServiceImpl implements AccountService {

    static final String SELF = "self";

    private final UserAccountRepository users;
    private final UserAccessRepository access;
    private final AccessChangeLog changeLog;
    private final SignUpPolicy signUpPolicy;
    private final LandingPolicy landing;
    private final PasswordHasher hasher;
    private final CodeIssuer codes;
    private final InvitationRedeemer invitations;
    private final DomainEventPublisher events;
    private final Clock clock;

    AccountServiceImpl(UserAccountRepository users, UserAccessRepository access, AccessChangeLog changeLog,
                       SignUpPolicy signUpPolicy, LandingPolicy landing, PasswordHasher hasher,
                       CodeIssuer codes, InvitationRedeemer invitations, DomainEventPublisher events,
                       Clock clock) {
        this.users = users;
        this.access = access;
        this.changeLog = changeLog;
        this.signUpPolicy = signUpPolicy;
        this.landing = landing;
        this.hasher = hasher;
        this.codes = codes;
        this.invitations = invitations;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(noRollbackFor = IdentityRuleViolation.class)
    public SignUpResult signUp(SignUpCommand c) {
        SignUpIntent intent = parseIntent(c.intent());
        ValidatedSignUp v = signUpPolicy.validate(new SignUpDetails(c.firstName(), c.lastName(), c.email(),
                c.phone(), c.phoneRegion(), c.password(), c.confirmPassword(), c.termsAccepted()));
        if (landing.isStaffEmail(v.email())) {
            throw new IdentityRuleViolation("identity.staff-email",
                    "Staff accounts are created by an access administrator",
                    Map.of("email", "Use your personal email, or ask your manager for staff access"));
        }
        // Checked before anything is written (this transaction does not roll back on rule violations).
        OrgInvitation invitation = c.invited() ? invitations.requirePending(c.invitationToken(), v.email()) : null;
        boolean invited = invitation != null;
        Instant now = Instant.now(clock);
        String hash = hasher.hash(c.password());
        Optional<UserAccount> existing = users.findByEmail(v.email());
        UserAccount user;
        if (existing.isPresent()) {
            if (existing.get().emailVerified()) {
                throw new ConflictException("identity.email-taken",
                        "An account with this email already exists. Sign in instead");
            }
            // Pending registration: the person started again ("use a different email" or a fresh attempt).
            UserAccount restarted = existing.get().restartedWith(v.firstName(), v.lastName(), v.phoneE164(), hash,
                    c.smsConsent(), now);
            user = users.update(invited ? restarted.verified(now) : restarted);
        } else {
            user = users.insert(new UserAccount(UUID.randomUUID(), v.firstName(), v.lastName(), v.email(), invited,
                    v.phoneE164(), hash, true, c.smsConsent(), now, null, now, now, 0));
        }
        List<String> granted = new ArrayList<>();
        for (String role : intent.roles()) {
            if (access.grantRole(user.id(), role, SELF)) {
                changeLog.append(SELF, "role.granted", user.id(), null, role, "sign-up intent " + intent.name());
            }
            granted.add(role);
        }
        if (existing.isEmpty()) {
            events.publish(IdentityEvents.USER_REGISTERED, IdentityEvents.AGGREGATE, user.id().toString(),
                    new UserRegistered(user.id().toString(), user.email(), user.firstName(), user.lastName(),
                            user.phone(), intent.name(), granted, "PASSWORD", invited, user.smsConsent(), now));
        }
        if (invited) {
            // The invitation link was emailed to this address, which proves the mailbox: no code needed.
            invitations.redeem(invitation, user);
            return new SignUpResult(user.id(), user.email(), false, null);
        }
        Instant expires = codes.issue(user, VerificationPurpose.EMAIL_VERIFICATION, "EMAIL", user.email());
        return new SignUpResult(user.id(), user.email(), true, expires);
    }

    @Override
    @Transactional(noRollbackFor = IdentityRuleViolation.class)
    public void verifyEmail(String email, String code) {
        UserAccount user = find(email);
        if (user.emailVerified()) {
            return;
        }
        codes.consume(user.id(), VerificationPurpose.EMAIL_VERIFICATION, code);
        Instant now = Instant.now(clock);
        UserAccount verified = users.update(user.verified(now));
        events.publish(IdentityEvents.USER_EMAIL_VERIFIED, IdentityEvents.AGGREGATE, verified.id().toString(),
                new UserEmailVerified(verified.id().toString(), verified.email(), now));
    }

    @Override
    @Transactional
    public Instant resendVerification(String email) {
        UserAccount user = find(email);
        if (user.emailVerified()) {
            throw new ConflictException("identity.already-verified", "This email is already confirmed. Sign in");
        }
        return codes.issue(user, VerificationPurpose.EMAIL_VERIFICATION, "EMAIL", user.email());
    }

    private UserAccount find(String email) {
        return users.findByEmail(UserAccount.normaliseEmail(email))
                .orElseThrow(() -> new NotFoundException("identity.account-not-found", "No sign-up found for that email"));
    }

    private static SignUpIntent parseIntent(String intent) {
        try {
            return SignUpIntent.parse(intent);
        } catch (IllegalArgumentException ex) {
            throw new IdentityRuleViolation("identity.invalid-intent", "Unknown sign-up entry point",
                    Map.of("intent", "Use shop, sell or provide"));
        }
    }
}
