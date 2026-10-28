package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.StepUp;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.StepUpStore;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StepUpServiceImpl implements StepUpService {

    private static final String METHOD = "STEP_UP";
    private static final int PROOF_BYTES = 32;
    private static final String REQUIRED_MESSAGE =
            "Confirm your password to continue, or sign in again and retry within five minutes";

    private final CurrentUser current;
    private final PermissionChecker permissions;
    private final UserAccountRepository users;
    private final PasswordHasher hasher;
    private final SignInProtectionService protection;
    private final StepUpStore proofs;
    private final AccessChangeLog changeLog;
    private final Clock clock;
    private final Duration freshSignIn;

    StepUpServiceImpl(CurrentUser current, PermissionChecker permissions, UserAccountRepository users,
                      PasswordHasher hasher, SignInProtectionService protection, StepUpStore proofs,
                      AccessChangeLog changeLog, Clock clock,
                      @Value("${at.identity.step-up.fresh-sign-in:PT5M}") Duration freshSignIn) {
        this.current = current;
        this.permissions = permissions;
        this.users = users;
        this.hasher = hasher;
        this.protection = protection;
        this.proofs = proofs;
        this.changeLog = changeLog;
        this.clock = clock;
        this.freshSignIn = freshSignIn;
    }

    @Override
    @Transactional
    public StepUpProof verify(String password) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        UUID id = current.id();
        UserAccount user = users.findById(id).orElseThrow(() -> new ForbiddenActionException(
                "identity.user-token-required", "This endpoint needs a user access token"));
        if (!user.hasPassword()) {
            throw new IdentityRuleViolation("identity.step-up-no-password",
                    "This account has no password. Sign in again and retry within five minutes",
                    Map.of("password", "This account has no password; sign in again instead"));
        }
        // Counted per account (not per IP: the BFF calls on behalf of every staff member), like a password sign-in.
        SignInAttempt attempt = new SignInAttempt(id, UserAccount.normaliseEmail(user.email()), null, null, null,
                SignInAttempt.FIRST_PARTY, METHOD, "bad-credentials");
        protection.requireNotLocked(attempt);
        if (password == null || password.isEmpty() || !hasher.matches(password, user.passwordHash())) {
            protection.recordFailure(attempt);
            throw new IdentityRuleViolation("identity.step-up-failed", "That password isn't right",
                    Map.of("password", "That password isn't right"));
        }
        Instant now = Instant.now(clock);
        proofs.deleteExpired(now);
        String proof = SecretHashing.randomToken(PROOF_BYTES);
        Instant expires = now.plus(StepUp.PROOF_TTL);
        proofs.save(SecretHashing.sha256Hex(proof), id, now, expires);
        changeLog.append(actor.subject(), "step-up.verified", null, null, null,
                "password re-entered; proof valid until " + expires);
        return new StepUpProof(proof, expires);
    }

    @Override
    @Transactional(readOnly = true)
    public void require(String proof, Instant signedInAt) {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        UUID id = current.id();
        Instant now = Instant.now(clock);
        if (StepUp.freshSignIn(signedInAt, now, freshSignIn)) {
            return;
        }
        if (proof != null && !proof.isBlank() && proofs.isValid(SecretHashing.sha256Hex(proof.trim()), id, now)) {
            return;
        }
        throw new ForbiddenActionException("identity.step-up-required", REQUIRED_MESSAGE);
    }
}
