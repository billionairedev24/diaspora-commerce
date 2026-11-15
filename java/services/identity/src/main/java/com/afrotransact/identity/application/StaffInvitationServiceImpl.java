package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.identity.application.IdentityEventPayloads.Recipient;
import com.afrotransact.identity.application.IdentityEventPayloads.RoleChanged;
import com.afrotransact.identity.application.IdentityEventPayloads.StaffInvitationCreated;
import com.afrotransact.identity.application.IdentityEventPayloads.UserRegistered;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.PhoneCheck;
import com.afrotransact.identity.domain.PhoneNumberPolicy;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.RoleKeys;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.identity.domain.StaffInvitation;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.StaffInvitationRepository;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StaffInvitationServiceImpl implements StaffInvitationService {

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_PAGE = 100;
    private static final int REASON_MIN = 3;
    private static final int REASON_MAX = 300;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final CurrentUser current;
    private final PermissionChecker permissions;
    private final StaffInvitationRepository invitations;
    private final UserAccountRepository users;
    private final UserAccessRepository access;
    private final RoleRepository roles;
    private final AccessChangeLog changeLog;
    private final SignUpPolicy signUpPolicy;
    private final PasswordPolicy passwordPolicy;
    private final PhoneNumberPolicy phones;
    private final PasswordHasher hasher;
    private final StaffInvitationSettings settings;
    private final DomainEventPublisher events;
    private final Clock clock;

    StaffInvitationServiceImpl(CurrentUser current, PermissionChecker permissions,
                               StaffInvitationRepository invitations, UserAccountRepository users,
                               UserAccessRepository access, RoleRepository roles, AccessChangeLog changeLog,
                               SignUpPolicy signUpPolicy, PasswordPolicy passwordPolicy, PhoneNumberPolicy phones,
                               PasswordHasher hasher, StaffInvitationSettings settings, DomainEventPublisher events,
                               Clock clock) {
        this.current = current;
        this.permissions = permissions;
        this.invitations = invitations;
        this.users = users;
        this.access = access;
        this.roles = roles;
        this.changeLog = changeLog;
        this.signUpPolicy = signUpPolicy;
        this.passwordPolicy = passwordPolicy;
        this.phones = phones;
        this.hasher = hasher;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public StaffInvitationView invite(InviteStaffCommand c) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        Map<String, String> errors = new LinkedHashMap<>();
        String first = trim(c.firstName());
        String last = trim(c.lastName());
        if (!signUpPolicy.validName(first)) {
            errors.put("firstName", "Use letters, spaces, hyphens or apostrophes");
        }
        if (!signUpPolicy.validName(last)) {
            errors.put("lastName", "Use letters, spaces, hyphens or apostrophes");
        }
        String email = UserAccount.normaliseEmail(c.email());
        if (email == null || !EMAIL.matcher(email).matches()) {
            errors.put("email", "Enter a valid work email address");
        } else if (!domainAllowed(email)) {
            errors.put("email", "Staff are invited with a " + String.join(" or ", settings.allowedEmailDomains())
                    + " address");
        }
        String reason = trim(c.reason());
        if (reason == null || reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            errors.put("reason", "Give a reason of " + REASON_MIN + " to " + REASON_MAX + " characters");
        }
        Role role = c.roleKey() == null ? null : roles.role(c.roleKey()).orElse(null);
        if (role == null || role.portal() != Portal.PLATFORM) {
            errors.put("roleKey", "Choose a staff role");
        } else if (RoleKeys.SUPER_ADMIN.equals(role.key()) && !actor.roles().contains(RoleKeys.SUPER_ADMIN)) {
            throw new ForbiddenActionException("identity.super-admin-invite",
                    "Only a Super Admin can invite another Super Admin");
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-staff-invitation", "Check the highlighted fields", errors);
        }
        if (users.findByEmail(email).isPresent()) {
            throw new ConflictException("identity.email-taken", "An account with this email already exists");
        }
        Instant now = Instant.now(clock);
        invitations.findPendingByEmail(email).ifPresent(old -> {
            invitations.revoke(old.revoked(actor.subject(), now));
            changeLog.append(actor.subject(), "staff.invitation.revoked", null, old.roleKey(), email,
                    "replaced by a new invitation");
        });
        String token = SecretHashing.randomToken(TOKEN_BYTES);
        StaffInvitation inv = new StaffInvitation(UUID.randomUUID(), email, first, last, role.key(), reason,
                SecretHashing.sha256Hex(token), InvitationStatus.PENDING, actor.subject(), now,
                now.plus(settings.ttl()), now, 1, null, null, null, null);
        invitations.insert(inv);
        changeLog.append(actor.subject(), "staff.invitation.created", null, role.key(), email, reason);
        publish(inv, role, token, false, actor);
        return view(inv, role.name(), inviterName(inv.invitedBy()), now);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<StaffInvitationView> open(int page, int size) {
        requireRead();
        int s = Math.max(1, Math.min(size, MAX_PAGE));
        int p = Math.max(0, page);
        Instant now = Instant.now(clock);
        List<StaffInvitationView> items = new ArrayList<>();
        for (StaffInvitation i : invitations.open(s, p * s)) {
            items.add(view(i, roleName(i.roleKey()), inviterName(i.invitedBy()), now));
        }
        return new PageView<>(items, p, s, invitations.countOpen());
    }

    @Override
    @Transactional
    public StaffInvitationView resend(UUID invitationId) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        StaffInvitation inv = find(invitationId);
        if (inv.status() != InvitationStatus.PENDING) {
            throw new ConflictException("identity.staff-invitation-not-open",
                    "This invitation is " + inv.status().name().toLowerCase(Locale.ROOT) + " and can't be sent again");
        }
        if (users.findByEmail(inv.email()).isPresent()) {
            throw new ConflictException("identity.email-taken", "An account with this email already exists");
        }
        Instant now = Instant.now(clock);
        String token = SecretHashing.randomToken(TOKEN_BYTES);
        StaffInvitation next = inv.reissued(SecretHashing.sha256Hex(token), now, now.plus(settings.ttl()));
        if (!invitations.reissue(next)) {
            throw new ConflictException("identity.staff-invitation-not-open", "This invitation is no longer open");
        }
        Role role = roles.role(inv.roleKey()).orElseThrow(() -> new NotFoundException("identity.role-not-found",
                "Unknown role " + inv.roleKey()));
        changeLog.append(actor.subject(), "staff.invitation.resent", null, inv.roleKey(), inv.email(),
                "sent again, valid until " + next.expiresAt());
        publish(next, role, token, true, actor);
        return view(next, role.name(), inviterName(next.invitedBy()), now);
    }

    @Override
    @Transactional
    public void revoke(UUID invitationId, String reason) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        StaffInvitation inv = find(invitationId);
        Instant now = Instant.now(clock);
        if (inv.status() != InvitationStatus.PENDING) {
            throw new ConflictException("identity.staff-invitation-not-open",
                    "This invitation is " + inv.status().name().toLowerCase(Locale.ROOT) + " and can't be revoked");
        }
        String why = trim(reason);
        if (why == null || why.length() < REASON_MIN || why.length() > REASON_MAX) {
            throw new IdentityRuleViolation("identity.invalid-staff-invitation", "Give a reason",
                    Map.of("reason", "Give a reason of " + REASON_MIN + " to " + REASON_MAX + " characters"));
        }
        if (!invitations.revoke(inv.revoked(actor.subject(), now))) {
            throw new ConflictException("identity.staff-invitation-not-open", "This invitation is no longer open");
        }
        changeLog.append(actor.subject(), "staff.invitation.revoked", null, inv.roleKey(), inv.email(), why);
    }

    @Override
    @Transactional(readOnly = true)
    public StaffInvitationPreview preview(String token) {
        StaffInvitation inv = byToken(token);
        return new StaffInvitationPreview(inv.firstName(), inv.lastName(), inv.email(), roleName(inv.roleKey()),
                inv.statusAt(Instant.now(clock)), inv.expiresAt());
    }

    @Override
    @Transactional
    public AcceptedStaffInvitation accept(AcceptStaffInvitationCommand c) {
        StaffInvitation inv = byToken(c.token());
        Instant now = Instant.now(clock);
        switch (inv.statusAt(now)) {
            case EXPIRED -> throw new IdentityRuleViolation("identity.staff-invitation-expired",
                    "This invitation has expired. Ask for a new one");
            case REVOKED -> throw new IdentityRuleViolation("identity.staff-invitation-revoked",
                    "This invitation was withdrawn. Ask for a new one");
            case ACCEPTED -> throw new IdentityRuleViolation("identity.staff-invitation-used",
                    "This invitation was already used. Sign in instead");
            case PENDING -> { }
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String local = inv.email().substring(0, Math.max(0, inv.email().indexOf('@')));
        List<String> violations = passwordPolicy.violations(c.password(),
                List.of(inv.firstName(), inv.lastName(), local));
        if (!violations.isEmpty()) {
            errors.put("password", String.join("; ", violations));
        } else if (c.confirmPassword() == null || !c.password().equals(c.confirmPassword())) {
            errors.put("confirmPassword", "The two passwords don't match");
        }
        String phoneE164 = null;
        if (c.phone() != null && !c.phone().isBlank()) {
            PhoneCheck phone = phones.check(c.phone(), c.phoneRegion());
            if (phone.valid()) {
                phoneE164 = phone.e164();
            } else {
                errors.put("phone", phone.error());
            }
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-user", "Check the highlighted fields", errors);
        }
        if (users.findByEmail(inv.email()).isPresent()) {
            throw new ConflictException("identity.email-taken", "An account with this email already exists");
        }
        Role role = roles.role(inv.roleKey()).orElseThrow(() -> new NotFoundException("identity.role-not-found",
                "Unknown role " + inv.roleKey()));
        UserAccount created = users.insert(new UserAccount(UUID.randomUUID(), inv.firstName(), inv.lastName(),
                inv.email(), true, phoneE164, hasher.hash(c.password()), true, false, now, null, now, now, 0));
        if (!invitations.claim(inv.id(), created.id(), now)) {
            throw new ConflictException("identity.staff-invitation-used", "This invitation was just used");
        }
        String actorId = created.id().toString();
        changeLog.append(actorId, "staff.invitation.accepted", created.id(), inv.roleKey(), inv.email(),
                "invited by " + inviterName(inv.invitedBy()) + ": " + inv.reason());
        if (access.grantRole(created.id(), inv.roleKey(), inv.invitedBy())) {
            changeLog.append(inv.invitedBy(), "role.granted", created.id(), inv.roleKey(), inv.roleKey(), inv.reason());
            events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, actorId,
                    new RoleChanged(actorId, inv.roleKey(), null, inv.invitedBy(), inv.reason(), now));
        }
        events.publish(IdentityEvents.USER_REGISTERED, IdentityEvents.AGGREGATE, actorId,
                new UserRegistered(actorId, inv.email(), inv.firstName(), inv.lastName(), phoneE164, "STAFF",
                        List.of(inv.roleKey()), "INVITATION", true, false, now));
        return new AcceptedStaffInvitation(created.id(), inv.email(), inv.roleKey(), role.name());
    }

    private void publish(StaffInvitation inv, Role role, String token, boolean resent, Actor actor) {
        events.publish(IdentityEvents.STAFF_INVITATION_CREATED, IdentityEvents.AGGREGATE, inv.id().toString(),
                new StaffInvitationCreated(inv.id().toString(), inv.email(), inv.firstName(), inv.lastName(),
                        role.key(), role.name(), settings.link(token), inv.expiresAt(), settings.ttl().toHours(),
                        resent, actor.subject(), inviterName(actor.subject()), new Recipient(null, inv.email(), null),
                        true));
    }

    private StaffInvitation byToken(String token) {
        String t = token == null ? "" : token.trim();
        return invitations.findByTokenHash(SecretHashing.sha256Hex(t)).orElseThrow(() -> new NotFoundException(
                "identity.staff-invitation-not-found", "This invitation link isn't valid"));
    }

    private StaffInvitation find(UUID id) {
        return invitations.findById(id).orElseThrow(() -> new NotFoundException(
                "identity.staff-invitation-not-found", "Invitation not found"));
    }

    private boolean domainAllowed(String email) {
        if (settings.allowedEmailDomains().isEmpty()) {
            return true;
        }
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        return settings.allowedEmailDomains().stream().anyMatch(d -> d.equalsIgnoreCase(domain));
    }

    private void requireRead() {
        Actor actor = current.actor();
        if (!actor.can(IdentityPermissions.ACCESS_READ) && !actor.can(IdentityPermissions.ACCESS_ADMIN)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + IdentityPermissions.ACCESS_READ);
        }
    }

    private String roleName(String roleKey) {
        return roles.role(roleKey).map(Role::name).orElse(roleKey);
    }

    private String inviterName(String subject) {
        try {
            Optional<UserAccount> u = users.findById(UUID.fromString(subject));
            return u.map(UserAccount::displayName).orElse(subject);
        } catch (IllegalArgumentException ex) {
            return subject;
        }
    }

    private static StaffInvitationView view(StaffInvitation i, String roleName, String invitedByName, Instant now) {
        return new StaffInvitationView(i.id(), i.email(), i.firstName(), i.lastName(), i.roleKey(), roleName,
                i.statusAt(now), i.reason(), i.invitedBy(), invitedByName, i.createdAt(), i.expiresAt(),
                i.lastSentAt(), i.sendCount());
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
