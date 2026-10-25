package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.StepUp;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.StepUpStore;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StepUpServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final UUID USER = UUID.fromString("3549a530-9cd8-41f9-8fe6-fc77932b3406");
    private static final String PASSWORD = "Plantain-Jollof-2026";
    private static final String HASH = "{bcrypt}hash-of-the-password";

    private final CurrentUser current = mock(CurrentUser.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final SignInProtectionService protection = mock(SignInProtectionService.class);
    private final StepUpStore proofs = mock(StepUpStore.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final StepUpServiceImpl service = new StepUpServiceImpl(current, permissions, users, hasher, protection,
            proofs, changeLog, Clock.fixed(NOW, ZoneOffset.UTC), StepUp.FRESH_SIGN_IN);

    StepUpServiceImplTest() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenReturn(new Actor(USER.toString(),
                PrincipalType.STAFF, Optional.empty(), Set.of("platform.super_admin"), Set.of("identity:admin")));
        when(current.id()).thenReturn(USER);
        when(users.findById(USER)).thenReturn(Optional.of(account(HASH)));
        when(hasher.matches(PASSWORD, HASH)).thenReturn(true);
    }

    private static UserAccount account(String passwordHash) {
        return new UserAccount(USER, "Platform", "Admin", "Admin@AfroTransact.com", true, "+17132490142",
                passwordHash, true, false, NOW, null, NOW, NOW, 0);
    }

    @Test
    void theRightPasswordIssuesAFiveMinuteProofStoredOnlyAsAHash() {
        StepUpService.StepUpProof proof = service.verify(PASSWORD);

        assertThat(proof.proof()).hasSizeGreaterThan(40);
        assertThat(proof.expiresAt()).isEqualTo(NOW.plus(StepUp.PROOF_TTL));
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(proofs).save(stored.capture(), eq(USER), eq(NOW), eq(NOW.plus(Duration.ofMinutes(5))));
        assertThat(stored.getValue()).isEqualTo(SecretHashing.sha256Hex(proof.proof())).doesNotContain(proof.proof());
        verify(proofs).deleteExpired(NOW);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq(USER.toString()), eq("step-up.verified"), eq(null), eq(null), eq(null),
                detail.capture());
        assertThat(detail.getValue()).doesNotContain(proof.proof()).doesNotContain(PASSWORD);
    }

    @Test
    void aWrongPasswordIsCountedByTheSignInProtectionAndIssuesNothing() {
        assertThatThrownBy(() -> service.verify("not-the-password")).isInstanceOf(IdentityRuleViolation.class)
                .satisfies(e -> {
                    IdentityRuleViolation violation = (IdentityRuleViolation) e;
                    assertThat(violation.code()).isEqualTo("identity.step-up-failed");
                    assertThat(violation.fieldErrors()).containsKey("password");
                });
        verify(protection).recordFailure(any(SignInAttempt.class));
        verify(proofs, never()).save(anyString(), any(), any(), any());
    }

    @Test
    void anEmptyPasswordIsRefusedAndAnAccountWithoutAPasswordCannotStepUpWithOne() {
        assertThatThrownBy(() -> service.verify("")).isInstanceOf(IdentityRuleViolation.class);
        when(users.findById(USER)).thenReturn(Optional.of(account(null)));
        assertThatThrownBy(() -> service.verify(PASSWORD)).isInstanceOf(IdentityRuleViolation.class)
                .extracting(e -> ((IdentityRuleViolation) e).code()).isEqualTo("identity.step-up-no-password");
        verify(proofs, never()).save(anyString(), any(), any(), any());
    }

    @Test
    void aLockedAccountIsStoppedBeforeThePasswordIsChecked() {
        doThrow(new RateLimitExceeded("identity.sign-in-locked", "Too many attempts", Duration.ofMinutes(5)))
                .when(protection).requireNotLocked(any());

        assertThatThrownBy(() -> service.verify(PASSWORD))
                .isInstanceOf(RateLimitExceeded.class);
        verify(hasher, never()).matches(anyString(), anyString());
    }

    @Test
    void aSignInFromTheLastFiveMinutesPasses() {
        service.require(null, NOW.minus(Duration.ofMinutes(4)));
        service.require("", NOW);
    }

    @Test
    void theFreshSignInWindowIsConfigurable() {
        StepUpServiceImpl never = new StepUpServiceImpl(current, permissions, users, hasher, protection, proofs,
                changeLog, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO);
        assertThatThrownBy(() -> never.require(null, NOW)).isInstanceOf(ForbiddenActionException.class);
        StepUpServiceImpl longer = new StepUpServiceImpl(current, permissions, users, hasher, protection, proofs,
                changeLog, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        longer.require(null, NOW.minus(Duration.ofMinutes(9)));
    }

    @Test
    void anOldOrUnknownSignInWithoutAProofIsRefused() {
        for (Instant signedIn : new Instant[] {null, NOW.minus(Duration.ofMinutes(6)), NOW.plus(Duration.ofHours(1))}) {
            assertThatThrownBy(() -> service.require(null, signedIn)).isInstanceOf(ForbiddenActionException.class)
                    .hasMessageContaining("Confirm your password");
        }
    }

    @Test
    void aValidProofOfThisUserPassesAndAnyOtherIsRefused() {
        String proof = "proof-of-this-user";
        when(proofs.isValid(SecretHashing.sha256Hex(proof), USER, NOW)).thenReturn(true);

        service.require(proof, NOW.minus(Duration.ofHours(2)));
        service.require(" " + proof + " ", null);

        assertThatThrownBy(() -> service.require("someone-elses-proof", NOW.minus(Duration.ofHours(2))))
                .isInstanceOf(ForbiddenActionException.class)
                .extracting(e -> ((ForbiddenActionException) e).code()).isEqualTo("identity.step-up-required");
    }
}
