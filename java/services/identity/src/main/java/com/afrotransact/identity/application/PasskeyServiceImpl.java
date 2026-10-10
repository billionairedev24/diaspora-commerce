package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.Passkey;
import com.afrotransact.identity.domain.PasskeyRegistration;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasskeyRegistrationStore;
import com.afrotransact.identity.port.PasskeyRegistrations;
import com.afrotransact.identity.port.PasskeyStore;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PasskeyServiceImpl implements PasskeyService {

    static final int MAX_LABEL = 64;
    static final int MAX_PASSKEYS = 20;

    private final CurrentUser current;
    private final UserAccountRepository users;
    private final PasskeyStore passkeys;
    private final PasskeyRegistrations registrations;
    private final PasskeyRegistrationStore pending;
    private final SignInMethodsReader methods;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    PasskeyServiceImpl(CurrentUser current, UserAccountRepository users, PasskeyStore passkeys,
                       PasskeyRegistrations registrations, PasskeyRegistrationStore pending,
                       SignInMethodsReader methods, AccessChangeLog changeLog, Clock clock) {
        this.current = current;
        this.users = users;
        this.passkeys = passkeys;
        this.registrations = registrations;
        this.pending = pending;
        this.methods = methods;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PasskeyRegistrationView startRegistration(String label) {
        UUID id = current.id();
        UserAccount u = account(id);
        if (passkeys.list(id).size() >= MAX_PASSKEYS) {
            throw new IdentityRuleViolation("identity.too-many-passkeys", "Remove a passkey before adding another (at "
                    + "most " + MAX_PASSKEYS + ")");
        }
        Instant now = Instant.now(clock);
        pending.purgeExpired(now.minus(Duration.ofDays(1)));
        PasskeyRegistrations.RegistrationRequest request = registrations.newRegistration(id, u.displayName());
        PasskeyRegistration registration = PasskeyRegistration.start(id, request.challenge(), request.userHandle(),
                request.rpId(), label(label), now);
        pending.insert(registration);
        return new PasskeyRegistrationView(registration.id(), registration.expiresAt(), request.optionsJson());
    }

    @Override
    @Transactional(noRollbackFor = IdentityRuleViolation.class)
    public Passkey completeRegistration(UUID registrationId, String credentialJson, String label) {
        UUID id = current.id();
        Instant now = Instant.now(clock);
        PasskeyRegistration registration = pending.lock(registrationId)
                .filter(r -> r.usableBy(id, now))
                .orElseThrow(() -> new ConflictException("identity.passkey-registration-expired",
                        "This passkey setup expired or was already used; start again"));
        if (credentialJson == null || credentialJson.isBlank()) {
            throw new IdentityRuleViolation("identity.passkey-rejected", "The passkey response is missing",
                    Map.of("credential", "Required"));
        }
        String name = label(label == null ? registration.label() : label);
        pending.complete(registration.id(), now);
        Passkey passkey = registrations.register(new PasskeyRegistrations.RegistrationRequest(
                        registration.challenge(), registration.userHandle(), registration.rpId(), null), credentialJson,
                        name)
                .orElseThrow(() -> new IdentityRuleViolation("identity.passkey-rejected",
                        "That passkey couldn't be registered. Try again on this device",
                        Map.of("credential", "Rejected")));
        changeLog.append(id.toString(), "passkey.registered", id, null, passkey.credentialId(), name);
        return passkey;
    }

    @Override
    @Transactional
    public Passkey rename(String credentialId, String label) {
        UUID id = current.id();
        String name = label(label);
        if (name == null) {
            throw new IdentityRuleViolation("identity.invalid-passkey-label", "Give the passkey a name",
                    Map.of("label", "Required"));
        }
        if (!registrations.rename(id, credentialId, name)) {
            throw new NotFoundException("identity.passkey-not-found", "Passkey not found");
        }
        changeLog.append(id.toString(), "passkey.renamed", id, null, credentialId, name);
        return passkeys.list(id).stream().filter(p -> p.credentialId().equals(credentialId)).findFirst()
                .orElseThrow(() -> new NotFoundException("identity.passkey-not-found", "Passkey not found"));
    }

    private UserAccount account(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }

    /** Trimmed label of at most {@value #MAX_LABEL} characters; null when blank. */
    private static String label(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        if (t.length() > MAX_LABEL) {
            throw new IdentityRuleViolation("identity.invalid-passkey-label", "Use at most " + MAX_LABEL
                    + " characters", Map.of("label", "Too long"));
        }
        return t;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Passkey> list() {
        return passkeys.list(current.id());
    }

    @Override
    @Transactional
    public void delete(String credentialId) {
        UUID id = current.id();
        UserAccount u = users.findById(id).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
        boolean owned = passkeys.list(id).stream().anyMatch(p -> p.credentialId().equals(credentialId));
        if (!owned) {
            throw new NotFoundException("identity.passkey-not-found", "Passkey not found");
        }
        if (!methods.read(u).canRemovePasskey()) {
            throw new ConflictException("identity.last-sign-in-method",
                    "Add a password or another passkey before removing this one");
        }
        passkeys.delete(id, credentialId);
        changeLog.append(id.toString(), "passkey.removed", id, null, credentialId, null);
    }
}
