package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MfaRulesTest {

    private final MfaPolicy policy = new DefaultMfaPolicy();
    private final MfaEnrolment none = new MfaEnrolment(false, false, null, 0, 0);
    private final MfaEnrolment totp = new MfaEnrolment(true, false, null, 10, 0);

    @Test
    void staffAlwaysNeedASecondFactorAndCannotDisableIt() {
        assertThat(policy.secondFactorRequired(true, none, false, false)).isTrue();
        assertThat(policy.mayDisable(true)).isFalse();
        assertThat(policy.mayDisable(false)).isTrue();
    }

    @Test
    void staffSwitchMethodsButNeverDropTheLastFactor() {
        MfaEnrolment both = new MfaEnrolment(true, true, "+17135550142", 10, 0);
        MfaEnrolment smsOnly = new MfaEnrolment(false, true, "+17135550142", 10, 0);
        assertThat(policy.mayRemove(true, both, MfaFactor.TOTP)).isTrue();
        assertThat(policy.mayRemove(true, both, MfaFactor.SMS)).isTrue();
        assertThat(policy.mayRemove(true, totp, MfaFactor.TOTP)).isFalse();
        assertThat(policy.mayRemove(true, smsOnly, MfaFactor.SMS)).isFalse();
        assertThat(policy.mayRemove(true, both, MfaFactor.RECOVERY_CODE)).isFalse();
        assertThat(policy.mayRemove(false, totp, MfaFactor.TOTP)).isTrue();
        assertThat(policy.mayRemove(false, smsOnly, MfaFactor.SMS)).isTrue();
    }

    @Test
    void trustedBrowserAndPasskeySkipThePrompt() {
        assertThat(policy.secondFactorRequired(false, totp, false, false)).isTrue();
        assertThat(policy.secondFactorRequired(false, totp, true, false)).isFalse();
        assertThat(policy.secondFactorRequired(true, totp, false, true)).isFalse();
        assertThat(policy.secondFactorRequired(false, none, false, false)).isFalse();
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
