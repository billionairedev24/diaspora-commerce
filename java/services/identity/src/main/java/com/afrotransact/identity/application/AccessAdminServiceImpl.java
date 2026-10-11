package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.PermissionChanged;
import com.afrotransact.identity.application.IdentityEventPayloads.RoleChanged;
import com.afrotransact.identity.application.IdentityEventPayloads.UserRegistered;
import com.afrotransact.identity.application.IdentityEventPayloads.UserStatusChanged;
import com.afrotransact.identity.domain.AccessChange;
import com.afrotransact.identity.domain.AccessLevel;
import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.AccessProfile;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.ModuleDefinition;
import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.Permission;
import com.afrotransact.identity.domain.PhoneCheck;
import com.afrotransact.identity.domain.PhoneNumberPolicy;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.RoleKeys;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.ModuleAccessRepository;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.SessionStore;
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
class AccessAdminServiceImpl implements AccessAdminService {

    private static final int MAX_PAGE = 200;

    private final CurrentUser current;
    private final PermissionChecker permissions;
    private final UserAccountRepository users;
    private final UserAccessRepository access;
    private final RoleRepository roles;
    private final ModuleAccessRepository modules;
    private final AccessChangeLog changeLog;
    private final AccessProfileLoader profiles;
    private final AccessPolicy accessPolicy;
    private final SessionStore sessions;
    private final MfaRepository mfa;
    private final SignUpPolicy signUpPolicy;
    private final PasswordPolicy passwordPolicy;
    private final PhoneNumberPolicy phones;
    private final PasswordHasher hasher;
    private final DomainEventPublisher events;
    private final Clock clock;

    AccessAdminServiceImpl(CurrentUser current, PermissionChecker permissions, UserAccountRepository users,
                           UserAccessRepository access, RoleRepository roles, ModuleAccessRepository modules,
                           AccessChangeLog changeLog, AccessProfileLoader profiles, AccessPolicy accessPolicy,
                           SessionStore sessions, MfaRepository mfa, SignUpPolicy signUpPolicy,
                           PasswordPolicy passwordPolicy, PhoneNumberPolicy phones, PasswordHasher hasher,
                           DomainEventPublisher events, Clock clock) {
        this.current = current;
        this.permissions = permissions;
        this.users = users;
        this.access = access;
        this.roles = roles;
        this.modules = modules;
        this.changeLog = changeLog;
        this.profiles = profiles;
        this.accessPolicy = accessPolicy;
        this.sessions = sessions;
        this.mfa = mfa;
        this.signUpPolicy = signUpPolicy;
        this.passwordPolicy = passwordPolicy;
        this.phones = phones;
        this.hasher = hasher;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Permission> permissions() {
        requireRead();
        return roles.permissions();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Role> roles(Portal portal) {
        requireRead();
        return roles.roles(portal);
    }

    @Override
    @Transactional
    public Role setRolePermission(String roleKey, String permissionKey, boolean granted, String reason) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        Role role = role(roleKey);
        requirePermission(permissionKey);
        boolean has = role.permissions().contains(permissionKey);
        if (has != granted) {
            if (granted) {
                roles.addPermissionToRole(roleKey, permissionKey);
            } else {
                roles.removePermissionFromRole(roleKey, permissionKey);
            }
            String action = granted ? "role.permission.granted" : "role.permission.revoked";
            changeLog.append(actor.subject(), action, null, roleKey, permissionKey, reason);
            events.publish(granted ? IdentityEvents.PERMISSION_GRANTED : IdentityEvents.PERMISSION_REVOKED,
                    IdentityEvents.AGGREGATE, roleKey, new PermissionChanged(null, roleKey, permissionKey, actor.subject(), reason,
                            Instant.now(clock)));
        }
        return role(roleKey);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<UserAccessView> users(String query, int page, int size) {
        requirePeopleRead();
        int s = Math.max(1, Math.min(size, MAX_PAGE));
        int p = Math.max(0, page);
        String q = query == null ? "" : query.trim();
        List<UserAccessView> items = users.search(q, s, p * s).stream().map(this::view).toList();
        return new PageView<>(items, p, s, users.count(q));
    }

    @Override
    @Transactional(readOnly = true)
    public UserAccessView user(UUID userId) {
        requirePeopleRead();
        return view(find(userId));
    }

    @Override
    @Transactional
    public UserAccessView createStaffUser(CreateStaffUserCommand c) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        Map<String, String> errors = new LinkedHashMap<>();
        if (!signUpPolicy.validName(trim(c.firstName()))) {
            errors.put("firstName", "Use letters, spaces, hyphens or apostrophes");
        }
        if (!signUpPolicy.validName(trim(c.lastName()))) {
            errors.put("lastName", "Use letters, spaces, hyphens or apostrophes");
        }
        String email = UserAccount.normaliseEmail(c.email());
        PhoneCheck phone = phones.check(c.phone(), c.phoneRegion());
        if (!phone.valid()) {
            errors.put("phone", phone.error());
        }
        String local = email == null ? "" : email.substring(0, Math.max(0, email.indexOf('@')));
        List<String> pw = passwordPolicy.violations(c.password(), List.of(String.valueOf(c.firstName()),
                String.valueOf(c.lastName()), local));
        if (!pw.isEmpty()) {
            errors.put("password", String.join("; ", pw));
        }
        for (String r : c.roles()) {
            Optional<Role> role = roles.role(r);
            if (role.isEmpty() || role.get().portal() == Portal.SELLER || role.get().portal() == Portal.PROVIDER) {
                errors.put("roles", "Unknown or organisation role " + r);
            }
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-user", "Check the highlighted fields", errors);
        }
        if (users.findByEmail(email).isPresent()) {
            throw new ConflictException("identity.email-taken", "An account with this email already exists");
        }
        Instant now = Instant.now(clock);
        UserAccount created = users.insert(new UserAccount(UUID.randomUUID(), trim(c.firstName()),
                trim(c.lastName()), email, true, phone.e164(), hasher.hash(c.password()), true, false, now, null, now,
                now, 0));
        changeLog.append(actor.subject(), "user.created", created.id(), null, email, "staff provisioning");
        List<String> granted = new ArrayList<>();
        for (String r : c.roles()) {
            grantRoleInternal(actor.subject(), created.id(), r, "initial role");
            granted.add(r);
        }
        events.publish(IdentityEvents.USER_REGISTERED, IdentityEvents.AGGREGATE, created.id().toString(),
                new UserRegistered(created.id().toString(), email, created.firstName(), created.lastName(),
                        created.phone(), "STAFF", granted, "ADMIN", true, false, now));
        return view(created);
    }

    @Override
    @Transactional
    public UserAccessView setRole(UUID userId, String roleKey, boolean granted, String reason) {
        return setRole(userId, roleKey, granted, reason, null);
    }

    @Override
    @Transactional
    public UserAccessView setRole(UUID userId, String roleKey, boolean granted, String reason, String market) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        UserAccount user = find(userId);
        Role role = role(roleKey);
        if (role.portal() == Portal.SELLER || role.portal() == Portal.PROVIDER) {
            throw new IdentityRuleViolation("identity.org-role",
                    "Organisation roles are managed through the organisation's team settings");
        }
        String scoped = market == null || market.isBlank() ? null : marketCode(market);
        if (scoped != null && (role.portal() != Portal.PLATFORM || RoleKeys.SUPER_ADMIN.equals(roleKey))) {
            throw new IdentityRuleViolation("identity.role-not-market-scoped", "Only staff roles other than Super "
                    + "Admin can be scoped to a market", Map.of("market", "Not for " + roleKey));
        }
        Set<String> markets = access.roleMarkets(userId).getOrDefault(roleKey, Set.of());
        boolean holds = access.roleKeys(userId).contains(roleKey);
        if (granted) {
            if (scoped == null) {
                if (holds && !markets.isEmpty()) {
                    throw new ConflictException("identity.role-market-scoped", roleKey + " is scoped to "
                            + String.join(", ", markets) + "; remove those markets before granting it platform-wide");
                }
                grantRoleInternal(actor.subject(), userId, roleKey, reason);
            } else {
                if (holds && markets.isEmpty()) {
                    throw new ConflictException("identity.role-platform-wide", roleKey + " is held platform-wide; "
                            + "remove it before scoping it to " + scoped);
                }
                grantMarketRole(actor.subject(), userId, roleKey, scoped, reason);
            }
        } else if (scoped == null) {
            if (access.revokeRole(userId, roleKey)) {
                changeLog.append(actor.subject(), "role.revoked", userId, roleKey, roleKey, reason);
                Instant now = Instant.now(clock);
                if (markets.isEmpty()) {
                    events.publish(IdentityEvents.ROLE_REVOKED, IdentityEvents.AGGREGATE, userId.toString(),
                            new RoleChanged(userId.toString(), roleKey, null, actor.subject(), reason, now));
                }
                for (String m : markets) {
                    events.publish(IdentityEvents.ROLE_REVOKED, IdentityEvents.AGGREGATE, userId.toString(),
                            new RoleChanged(userId.toString(), roleKey, null, actor.subject(), reason, now, m));
                }
            }
        } else if (access.removeRoleMarket(userId, roleKey, scoped)) {
            if (markets.size() <= 1) {
                access.revokeRole(userId, roleKey);
            }
            changeLog.append(actor.subject(), "role.revoked", userId, roleKey, roleKey + "@" + scoped, reason);
            events.publish(IdentityEvents.ROLE_REVOKED, IdentityEvents.AGGREGATE, userId.toString(),
                    new RoleChanged(userId.toString(), roleKey, null, actor.subject(), reason, Instant.now(clock),
                            scoped));
        }
        return view(user);
    }

    @Override
    @Transactional
    public UserAccessView setPermission(UUID userId, String permissionKey, boolean granted, String reason) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        UserAccount user = find(userId);
        requirePermission(permissionKey);
        boolean changed = granted ? access.grantPermission(userId, permissionKey, actor.subject())
                : access.revokePermission(userId, permissionKey);
        if (changed) {
            changeLog.append(actor.subject(), granted ? "permission.granted" : "permission.revoked", userId, null,
                    permissionKey, reason);
            events.publish(granted ? IdentityEvents.PERMISSION_GRANTED : IdentityEvents.PERMISSION_REVOKED,
                    IdentityEvents.AGGREGATE, userId.toString(), new PermissionChanged(userId.toString(), null,
                            permissionKey, actor.subject(), reason, Instant.now(clock)));
        }
        return view(user);
    }

    @Override
    @Transactional
    public UserAccessView setEnabled(UUID userId, boolean enabled, String reason) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        UserAccount user = find(userId);
        if (!enabled && userId.toString().equals(actor.subject())) {
            throw new ForbiddenActionException("identity.cannot-disable-self", "You cannot disable your own account");
        }
        if (user.enabled() == enabled) {
            return view(user);
        }
        Instant now = Instant.now(clock);
        UserAccount updated = users.update(user.withEnabled(enabled, now));
        if (!enabled) {
            sessions.revokeAll(userId, Optional.empty());
            mfa.deleteTrustedBrowsers(userId);
        }
        changeLog.append(actor.subject(), enabled ? "user.enabled" : "user.disabled", userId, null, null, reason);
        events.publish(enabled ? IdentityEvents.USER_ENABLED : IdentityEvents.USER_DISABLED,
                IdentityEvents.AGGREGATE, userId.toString(),
                new UserStatusChanged(userId.toString(), actor.subject(), reason, now));
        return view(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public ModuleMatrix moduleMatrix(Portal portal) {
        requireRead();
        List<ModuleDefinition> defs = modules.modules(portal);
        return new ModuleMatrix(portal, defs, roles.roles(portal), modules.matrix(portal));
    }

    @Override
    @Transactional
    public ModuleAccess setModuleAccess(Portal portal, String moduleKey, String roleKey, AccessLevel level) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        Role role = role(roleKey);
        if (role.portal() != portal) {
            throw new IdentityRuleViolation("identity.wrong-portal", "Role " + roleKey + " is not a "
                    + portal.wire() + " role");
        }
        boolean known = modules.modules(portal).stream().anyMatch(m -> m.key().equals(moduleKey));
        if (!known) {
            throw new NotFoundException("identity.module-not-found", "Unknown module " + moduleKey);
        }
        AccessLevel previous = modules.set(portal, moduleKey, roleKey, level);
        if (previous != level) {
            changeLog.append(actor.subject(), "module.access.changed", null, roleKey, portal.wire() + ":" + moduleKey,
                    previous.wire() + " → " + level.wire());
        }
        return new ModuleAccess(portal, moduleKey, roleKey, level);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<AccessChange> changeLog(UUID userId, int page, int size) {
        requireRead();
        int s = Math.max(1, Math.min(size, MAX_PAGE));
        int p = Math.max(0, page);
        List<AccessChange> items = changeLog.recent(userId, s, p * s);
        return new PageView<>(items, p, s, -1);
    }

    /** Grants {@code roleKey} for one market (adds the role, scoped, when the user does not hold it yet). */
    private void grantMarketRole(String actorId, UUID userId, String roleKey, String market, String reason) {
        access.grantRole(userId, roleKey, actorId);
        if (access.addRoleMarket(userId, roleKey, market, actorId)) {
            changeLog.append(actorId, "role.granted", userId, roleKey, roleKey + "@" + market, reason);
            events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, userId.toString(),
                    new RoleChanged(userId.toString(), roleKey, null, actorId, reason, Instant.now(clock), market));
        }
    }

    /** Content's market code: ISO 3166-1 alpha-2, upper case, UK → GB. */
    private static String marketCode(String raw) {
        String c = raw.trim().toUpperCase(Locale.ROOT);
        c = "UK".equals(c) ? "GB" : c;
        if (!c.matches("[A-Z]{2}")) {
            throw new IdentityRuleViolation("identity.invalid-market", "Use a two-letter market code such as GB or US",
                    Map.of("market", "Invalid"));
        }
        return c;
    }

    private void grantRoleInternal(String actorId, UUID userId, String roleKey, String reason) {
        if (access.grantRole(userId, roleKey, actorId)) {
            changeLog.append(actorId, "role.granted", userId, roleKey, roleKey, reason);
            events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, userId.toString(),
                    new RoleChanged(userId.toString(), roleKey, null, actorId, reason, Instant.now(clock)));
        }
    }

    private void requireRead() {
        Actor actor = current.actor();
        if (!actor.can(IdentityPermissions.ACCESS_READ) && !actor.can(IdentityPermissions.ACCESS_ADMIN)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + IdentityPermissions.ACCESS_READ);
        }
    }

    /**
     * People search and one person: access control viewers, and staff with {@code users:read} (customer support finds
     * the customer for a support case or a pre-order late add).
     */
    private void requirePeopleRead() {
        if (!current.actor().can(IdentityPermissions.USERS_READ)) {
            requireRead();
        }
    }

    private void requirePermission(String key) {
        if (!roles.permissionExists(key)) {
            throw new NotFoundException("identity.permission-not-found", "Unknown permission " + key);
        }
    }

    private Role role(String key) {
        return roles.role(key).orElseThrow(() -> new NotFoundException("identity.role-not-found",
                "Unknown role " + key));
    }

    private UserAccount find(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }

    private UserAccessView view(UserAccount user) {
        AccessProfile profile = profiles.load(user);
        return new UserAccessView(user, profile.roleKeys(), profile.directGrants(), profile.memberships(),
                accessPolicy.resolve(profile), access.roleMarkets(user.id()));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
