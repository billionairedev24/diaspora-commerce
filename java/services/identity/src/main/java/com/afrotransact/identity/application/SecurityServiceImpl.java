package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.SignInMethods;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.UserSession;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.FederatedIdentityRepository;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.SessionStore;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SecurityServiceImpl implements SecurityService {

    private final CurrentUser current;
    private final UserAccountRepository users;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher hasher;
    private final SessionStore sessions;
    private final FederatedIdentityRepository federated;
    private final SignInMethodsReader methods;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    SecurityServiceImpl(CurrentUser current, UserAccountRepository users, PasswordPolicy passwordPolicy,
                        PasswordHasher hasher, SessionStore sessions, FederatedIdentityRepository federated,
                        SignInMethodsReader methods, AccessChangeLog changeLog, Clock clock) {
        this.current = current;
        this.users = users;
        this.passwordPolicy = passwordPolicy;
        this.hasher = hasher;
        this.sessions = sessions;
        this.federated = federated;
        this.methods = methods;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void changePassword(String currentPassword, String newPassword, String confirmPassword) {
        UserAccount u = user();
        Map<String, String> errors = new LinkedHashMap<>();
        if (u.hasPassword() && (currentPassword == null || !hasher.matches(currentPassword, u.passwordHash()))) {
            errors.put("currentPassword", "Your current password isn't right");
        }
        List<String> violations = passwordPolicy.violations(newPassword,
                List.of(u.firstName(), u.lastName(), u.emailLocalPart()));
        if (!violations.isEmpty()) {
            errors.put("newPassword", String.join("; ", violations));
        }
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            errors.put("confirmPassword", "Passwords don't match");
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-password-change", "Check the highlighted fields",
                    errors);
        }
        users.update(u.withPassword(hasher.hash(newPassword), Instant.now(clock)));
        changeLog.append(u.id().toString(), "password.changed", u.id(), null, "password", null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSession> sessions(Optional<String> currentAccessToken) {
        return sessions.list(current.id(), currentAccessToken);
    }

    @Override
    @Transactional
    public void revokeSession(String sessionId) {
        if (!sessions.revoke(current.id(), sessionId)) {
            throw new NotFoundException("identity.session-not-found", "Session not found");
        }
    }

    @Override
    @Transactional
    public int revokeOtherSessions(Optional<String> currentAccessToken) {
        return sessions.revokeAll(current.id(), currentAccessToken);
    }

    @Override
    @Transactional(readOnly = true)
    public LinkedAccountsView linkedAccounts() {
        UserAccount u = user();
        return new LinkedAccountsView(federated.forUser(u.id()), methods.read(u));
    }

    @Override
    @Transactional
    public LinkedAccountsView unlink(String provider) {
        UserAccount u = user();
        SignInMethods m = methods.read(u);
        if (!m.providers().contains(provider)) {
            throw new NotFoundException("identity.provider-not-linked", provider + " is not linked");
        }
        if (!m.canUnlink(provider)) {
            throw new ConflictException("identity.last-sign-in-method",
                    "Add a password or passkey before unlinking your last sign-in method");
        }
        federated.unlink(u.id(), provider);
        changeLog.append(u.id().toString(), "identity.unlinked", u.id(), null, provider, null);
        return linkedAccounts();
    }

    private UserAccount user() {
        return users.findById(current.id()).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }
}
