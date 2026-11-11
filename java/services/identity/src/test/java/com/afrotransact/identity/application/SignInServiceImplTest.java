package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.AuthSettings;
import com.afrotransact.identity.domain.DefaultMfaPolicy;
import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.EmailOtpEnrolment;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.MfaFactor;
import com.afrotransact.identity.domain.PrincipalType;
import com.afrotransact.identity.domain.RecoveryCodes;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.TotpAuthenticator;
import com.afrotransact.identity.domain.TotpEnrolment;
import com.afrotransact.identity.domain.TrustedBrowser;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.PasskeyStore;
import com.afrotransact.identity.port.UserAccountRepository;
import org.junit.jupiter.api.Test;

/** Which second step a person meets after their password, for every combination of enrolment and policy. */
class SignInServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final AuthSettings OFF = new AuthSettings(12, false, false, false, 30, 1440, null, null);
    private static final AuthSettings SELLERS_ON = new AuthSettings(12, false, true, false, 30, 1440, null, null);

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AccessProfileLoader profiles = mock(AccessProfileLoader.class);
    private final AccessPolicy accessPolicy = mock(AccessPolicy.class);
    private final MfaRepository mfa = mock(MfaRepository.class);
    private final PasskeyStore passkeys = mock(PasskeyStore.class);
    private final CodeIssuer codes = mock(CodeIssuer.class);
    private final AuthSettingsService authSettings = mock(AuthSettingsService.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final UserAccount ada = new UserAccount(UUID.randomUUID(), "Ada", "Okafor", "ada@example.com", true,
            "+17132490142", "{x}h", true, false, NOW, null, NOW, NOW, 0);

    private SignInServiceImpl service(boolean smsEnabled, AuthSettings settings, boolean staff, String... roles) {
        when(users.findById(ada.id())).thenReturn(Optional.of(ada));
        when(accessPolicy.resolve(any())).thenReturn(new EffectiveAccess(PrincipalType.CUSTOMER, Optional.empty(),
                Set.of(roles), Set.of(), staff));
        when(authSettings.effective()).thenReturn(settings);
        when(passkeys.list(ada.id())).thenReturn(List.of());
        return new SignInServiceImpl(users, profiles, accessPolicy, mfa, new MfaSupport(mfa, passkeys, smsEnabled),
                new DefaultMfaPolicy(), mock(TotpAuthenticator.class), codes, authSettings, changeLog,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void enrolTotp() {
        when(mfa.totp(ada.id())).thenReturn(Optional.of(new TotpEnrolment(ada.id(), "SECRET", NOW, NOW, 0)));
    }

    private void enrolEmail() {
        when(mfa.emailOtp(ada.id())).thenReturn(Optional.of(new EmailOtpEnrolment(ada.id(), true, NOW, null)));
    }

    private void enrolSmsRow() {
        when(mfa.smsPhone(ada.id())).thenReturn(Optional.of("+17132490142"));
    }

    @Test
    void noMfaMeansPasswordOnlyAndNothingIsSent() {
        SecondFactorChallenge c = service(false, OFF, false, "account.customer")
                .challenge(ada.id(), Optional.empty(), false);

        assertThat(c.pending()).isFalse();
        assertThat(c.required()).isFalse();
        assertThat(c.enrolmentRequired()).isFalse();
        verify(codes, never()).issue(any(), any(), any(), any());
    }

    @Test
    void aPhoneOnFileDoesNotTriggerAnOtpEvenWhenMfaIsMandatory() {
        SecondFactorChallenge c = service(true, SELLERS_ON, false, "seller.owner")
                .challenge(ada.id(), Optional.empty(), false);

        assertThat(c.required()).isFalse();
        assertThat(c.sms()).isFalse();
        assertThat(c.enrolmentRequired()).isTrue();
    }

    @Test
    void authenticatorOnlyAsksForTheAuthenticator() {
        enrolTotp();
        SecondFactorChallenge c = service(false, OFF, false, "account.customer")
                .challenge(ada.id(), Optional.empty(), false);

        assertThat(c.required()).isTrue();
        assertThat(c.totp()).isTrue();
        assertThat(c.emailOtp()).isFalse();
        assertThat(c.defaultFactor()).isEqualTo("TOTP");
        assertThat(c.emailIsDefault()).isFalse();
    }

    @Test
    void emailOnlyAsksForTheEmailCodeAndItIsSentWithoutAsking() {
        enrolEmail();
        SignInServiceImpl s = service(false, OFF, false, "account.customer");
        SecondFactorChallenge c = s.challenge(ada.id(), Optional.empty(), false);

        assertThat(c.required()).isTrue();
        assertThat(c.emailOtp()).isTrue();
        assertThat(c.totp()).isFalse();
        assertThat(c.emailHint()).isEqualTo("a••@example.com");
        assertThat(c.emailIsDefault()).isTrue();
        assertThat(s.sendEmailCodeIfDefault(ada.id())).isTrue();
        verify(codes).issue(ada, VerificationPurpose.SIGN_IN_EMAIL, "EMAIL", "ada@example.com");
    }

    @Test
    void bothFactorsDefaultToTheAuthenticatorAndEmailIsOnlySentOnRequest() {
        enrolTotp();
        enrolEmail();
        SignInServiceImpl s = service(false, OFF, false, "account.customer");
        SecondFactorChallenge c = s.challenge(ada.id(), Optional.empty(), false);

        assertThat(c.totp()).isTrue();
        assertThat(c.emailOtp()).isTrue();
        assertThat(c.defaultFactor()).isEqualTo("TOTP");
        assertThat(s.sendEmailCodeIfDefault(ada.id())).isFalse();
        verify(codes, never()).issue(any(), any(), any(), any());
    }

    @Test
    void anEmailCodeCannotBeSentToSomeoneWhoDidNotTurnEmailCodesOn() {
        SignInServiceImpl s = service(false, OFF, false, "account.customer");

        assertThatThrownBy(() -> s.sendEmailCode(ada.id())).isInstanceOf(IdentityRuleViolation.class);
        verify(codes, never()).issue(any(), any(), any(), any());
    }

    @Test
    void staffWithoutAFactorMustEnrolAndGetNoCode() {
        SecondFactorChallenge c = service(false, OFF, true, "platform.support")
                .challenge(ada.id(), Optional.empty(), false);

        assertThat(c.enrolmentRequired()).isTrue();
        assertThat(c.required()).isFalse();
        verify(codes, never()).issue(any(), any(), any(), any());
    }

    @Test
    void staffWithAFactorAnswersItAndNeedsNoEnrolment() {
        enrolTotp();
        SecondFactorChallenge c = service(false, OFF, true, "platform.support")
                .challenge(ada.id(), Optional.empty(), false);

        assertThat(c.required()).isTrue();
        assertThat(c.enrolmentRequired()).isFalse();
    }

    @Test
    void portalSwitchOnWithoutAFactorForcesEnrolmentOnlyForThatPortal() {
        assertThat(service(false, SELLERS_ON, false, "seller.owner").challenge(ada.id(), Optional.empty(), false)
                .enrolmentRequired()).isTrue();
        assertThat(service(false, SELLERS_ON, false, "account.customer")
                .challenge(ada.id(), Optional.empty(), false).pending()).isFalse();
    }

    @Test
    void aTrustedBrowserSkipsTheCode() {
        enrolTotp();
        when(mfa.trustedBrowser(eq(ada.id()), eq(SecretHashing.sha256Hex("tok")), any())).thenReturn(Optional.of(
                new TrustedBrowser(UUID.randomUUID(), ada.id(), "h", null, NOW, NOW.plusSeconds(60))));

        SecondFactorChallenge c = service(false, OFF, false, "account.customer")
                .challenge(ada.id(), Optional.of("tok"), false);

        assertThat(c.pending()).isFalse();
    }

    @Test
    void aPasskeySignInSatisfiesMandatoryMfa() {
        SecondFactorChallenge c = service(false, SELLERS_ON, true, "seller.owner")
                .challenge(ada.id(), Optional.empty(), true);

        assertThat(c.pending()).isFalse();
    }

    @Test
    void existingSmsEnrolmentsAreIgnoredWhileTheFlagIsOff() {
        enrolSmsRow();
        SignInServiceImpl s = service(false, OFF, false, "account.customer");

        SecondFactorChallenge c = s.challenge(ada.id(), Optional.empty(), false);

        assertThat(c.pending()).isFalse();
        assertThat(c.sms()).isFalse();
        assertThat(c.smsHint()).isNull();
        assertThatThrownBy(() -> s.sendSmsCode(ada.id())).isInstanceOf(IdentityRuleViolation.class);
        assertThat(s.verifySecondFactor(ada.id(), MfaFactor.SMS, "123456")).isFalse();
        verify(codes, never()).issue(any(), any(), any(), any());
    }

    @Test
    void smsIsOfferedOnlyOnceTheFlagIsOn() {
        enrolSmsRow();

        SecondFactorChallenge c = service(true, OFF, false, "account.customer")
                .challenge(ada.id(), Optional.empty(), false);

        assertThat(c.required()).isTrue();
        assertThat(c.sms()).isTrue();
        assertThat(c.smsHint()).endsWith("0142");
    }

    @Test
    void aSmsOnlyPersonIsTreatedAsHavingNoFactorWhileTheFlagIsOff() {
        enrolSmsRow();

        SecondFactorChallenge c = service(false, SELLERS_ON, false, "seller.owner")
                .challenge(ada.id(), Optional.empty(), false);

        assertThat(c.required()).isFalse();
        assertThat(c.enrolmentRequired()).isTrue();
    }

    @Test
    void anEmailCodeIsAcceptedOnceAndAuditedAndRecordsLastUse() {
        enrolEmail();
        SignInServiceImpl s = service(false, OFF, false, "account.customer");

        assertThat(s.verifySecondFactor(ada.id(), MfaFactor.EMAIL_OTP, "123456")).isTrue();

        verify(codes).consume(ada.id(), VerificationPurpose.SIGN_IN_EMAIL, "123456");
        verify(mfa).touchEmailOtp(eq(ada.id()), any());
        verify(changeLog).append(ada.id().toString(), "mfa.email-otp.used", ada.id(), null, "email-otp", null);
    }

    @Test
    void aWrongEmailCodeFailsWithoutTouchingTheEnrolment() {
        enrolEmail();
        SignInServiceImpl s = service(false, OFF, false, "account.customer");
        doThrow(new IdentityRuleViolation("identity.invalid-code", "no"))
                .when(codes).consume(ada.id(), VerificationPurpose.SIGN_IN_EMAIL, "000000");

        assertThat(s.verifySecondFactor(ada.id(), MfaFactor.EMAIL_OTP, "000000")).isFalse();
        verify(mfa, never()).touchEmailOtp(any(), any());
    }

    @Test
    void anEmailCodeIsRefusedWhenEmailCodesAreNotEnrolled() {
        SignInServiceImpl s = service(false, OFF, false, "account.customer");

        assertThat(s.verifySecondFactor(ada.id(), MfaFactor.EMAIL_OTP, "123456")).isFalse();
        verify(codes, never()).consume(any(), any(), any());
    }

    @Test
    void aRecoveryCodeWorksOnceAndIsAudited() {
        enrolTotp();
        SignInServiceImpl s = service(false, OFF, false, "account.customer");
        when(mfa.useRecoveryCode(eq(ada.id()), eq(RecoveryCodes.hash("abcde-fghjk")), any())).thenReturn(true);

        assertThat(s.verifySecondFactor(ada.id(), MfaFactor.RECOVERY_CODE, "abcde-fghjk")).isTrue();
        verify(changeLog).append(ada.id().toString(), "mfa.recovery-code.used", ada.id(), null, "recovery-code",
                null);
    }

    @Test
    void theSetupReminderIsOnlyDueForPeopleWithNoFactorWhoHaveNotSkippedLately() {
        SignInServiceImpl s = service(false, OFF, false, "account.customer");
        assertThat(s.setupPromptDue(ada.id())).isTrue();

        when(mfa.setupPromptDismissedAt(ada.id())).thenReturn(Optional.of(NOW.minusSeconds(3600)));
        assertThat(s.setupPromptDue(ada.id())).isFalse();

        when(mfa.setupPromptDismissedAt(ada.id())).thenReturn(Optional.empty());
        enrolEmail();
        assertThat(s.setupPromptDue(ada.id())).isFalse();
    }
}
