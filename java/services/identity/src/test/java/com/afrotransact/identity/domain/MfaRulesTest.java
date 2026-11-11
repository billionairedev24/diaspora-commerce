package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MfaRulesTest {

    private final MfaPolicy policy = new DefaultMfaPolicy();
    private final MfaEnrolment none = new MfaEnrolment(false, false, null, 0, 0);
    private final MfaEnrolment totp = new MfaEnrolment(true, false, null, 10, 0);
    private final MfaEnrolment email = new MfaEnrolment(false, false, null, 10, 0, true);

    @Test
    void noCodeIsEverAskedWithoutAnEnrolledFactor() {
        assertThat(policy.secondFactorRequired(none, false, false)).isFalse();
        assertThat(policy.secondFactorRequired(totp, false, false)).isTrue();
        assertThat(policy.secondFactorRequired(email, false, false)).isTrue();
        // existing SMS enrolments read as "not enrolled" while the flag is off (MfaSupport), so no factor remains
        assertThat(policy.secondFactorRequired(new MfaEnrolment(false, false, null, 10, 0), false, false)).isFalse();
    }

    @Test
    void mandatoryMeansForcedEnrolmentNotAnOtp() {
        assertThat(policy.enrolmentRequired(true, none, false)).isTrue();
        assertThat(policy.secondFactorRequired(none, false, false)).isFalse();
        // enrolled, not mandatory, passkey sign-in and plain accounts never enrol by force
        assertThat(policy.enrolmentRequired(true, totp, false)).isFalse();
        assertThat(policy.enrolmentRequired(true, none, true)).isFalse();
        assertThat(policy.enrolmentRequired(false, none, false)).isFalse();
    }

    @Test
    void mandatoryAccountsCannotDisableOrDropTheLastFactor() {
        MfaEnrolment both = new MfaEnrolment(true, false, null, 10, 0, true);
        assertThat(policy.mayDisable(true)).isFalse();
        assertThat(policy.mayDisable(false)).isTrue();
        assertThat(policy.mayRemove(true, both, MfaFactor.TOTP)).isTrue();
        assertThat(policy.mayRemove(true, both, MfaFactor.EMAIL_OTP)).isTrue();
        assertThat(policy.mayRemove(true, totp, MfaFactor.TOTP)).isFalse();
        assertThat(policy.mayRemove(true, email, MfaFactor.EMAIL_OTP)).isFalse();
        assertThat(policy.mayRemove(true, both, MfaFactor.RECOVERY_CODE)).isFalse();
        assertThat(policy.mayRemove(false, totp, MfaFactor.TOTP)).isTrue();
        assertThat(policy.mayRemove(false, email, MfaFactor.EMAIL_OTP)).isTrue();
    }

    @Test
    void trustedBrowserAndPasskeySkipThePrompt() {
        assertThat(policy.secondFactorRequired(totp, true, false)).isFalse();
        assertThat(policy.secondFactorRequired(totp, false, true)).isFalse();
        assertThat(policy.secondFactorRequired(email, true, false)).isFalse();
    }

    @Test
    void theSetupReminderIsGentle() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        assertThat(policy.reminderDue(none, false, null, now)).isTrue();
        assertThat(policy.reminderDue(none, false, now.minus(MfaPolicy.SETUP_REMINDER_INTERVAL).plusSeconds(60), now))
                .isFalse();
        assertThat(policy.reminderDue(none, false, now.minus(MfaPolicy.SETUP_REMINDER_INTERVAL), now)).isTrue();
        assertThat(policy.reminderDue(totp, false, null, now)).isFalse();
        assertThat(policy.reminderDue(none, true, null, now)).isFalse();
    }

    @Test
    void cannotRemoveTheLastSignInMethod() {
        assertThat(new SignInMethods(false, 0, Set.of("google")).canUnlink("google")).isFalse();
        assertThat(new SignInMethods(true, 0, Set.of("google")).canUnlink("google")).isTrue();
        assertThat(new SignInMethods(false, 1, Set.of("google")).canUnlink("google")).isTrue();
        assertThat(new SignInMethods(false, 1, Set.of()).canRemovePasskey()).isFalse();
        assertThat(new SignInMethods(true, 1, Set.of()).canRemovePasskey()).isTrue();
    }

    @Test
    void recoveryCodesAreUniqueAndNormalisedForHashing() {
        List<String> codes = RecoveryCodes.generate();
        assertThat(codes).hasSize(RecoveryCodes.COUNT).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("[a-z2-9]{5}-[a-z2-9]{5}"));
        assertThat(RecoveryCodes.hash(" ABCDE-FGHJK ")).isEqualTo(RecoveryCodes.hash("abcde-fghjk"));
    }

    @Test
    void signUpIntentDecidesRoles() {
        assertThat(SignUpIntent.parse("shop").roles()).containsExactly(RoleKeys.CUSTOMER);
        assertThat(SignUpIntent.parse("sell").roles()).containsExactly(RoleKeys.CUSTOMER, RoleKeys.SELLER_APPLICANT);
        assertThat(SignUpIntent.parse("PROVIDE").roles())
                .containsExactly(RoleKeys.CUSTOMER, RoleKeys.PROVIDER_APPLICANT);
        assertThat(SignUpIntent.parse(null)).isEqualTo(SignUpIntent.SHOP);
    }

    @Test
    void accessLevelsCycle() {
        assertThat(AccessLevel.NONE.next()).isEqualTo(AccessLevel.VIEW);
        assertThat(AccessLevel.FULL.next()).isEqualTo(AccessLevel.NONE);
    }
}
