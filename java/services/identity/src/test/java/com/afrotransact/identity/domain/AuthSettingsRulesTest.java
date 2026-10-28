package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AuthSettingsRulesTest {

    private static final AuthSettings DEFAULTS = new AuthSettings(12, false, false, false, 30, 1440, null, null);

    private static AuthSettingsOverrides with(Integer pw, Integer idle, Integer absolute, Integer access,
                                              Integer refresh) {
        return new AuthSettingsOverrides(pw, null, null, null, idle, absolute, access, refresh);
    }

    @Test
    void unsetFieldsFallBackToTheConfiguredDefaults() {
        AuthSettings s = AuthSettings.resolve(DEFAULTS, new AuthSettingsOverrides(16, true, null, null, null, 600,
                null, 4320));
        assertThat(s.passwordMinLength()).isEqualTo(16);
        assertThat(s.mfaRequiredAccount()).isTrue();
        assertThat(s.mfaRequiredSeller()).isFalse();
        assertThat(s.sessionIdleTimeoutMinutes()).isEqualTo(30);
        assertThat(s.sessionAbsoluteTimeoutMinutes()).isEqualTo(600);
        assertThat(s.accessTokenTtlCapMinutes()).isNull();
        assertThat(s.refreshTokenTtlCapMinutes()).isEqualTo(4320);
    }

    @Test
    void validValuesPass() {
        AuthSettingsOverrides o = with(10, 15, 480, 10, 10_080);
        AuthSettingsRules.validate(o, AuthSettings.resolve(DEFAULTS, o));
        AuthSettingsRules.validate(AuthSettingsOverrides.none(), DEFAULTS);
    }

    @Test
    void outOfRangeValuesAreRejectedPerField() {
        AuthSettingsOverrides o = with(4, 1, 5, 120, 1);
        assertThatThrownBy(() -> AuthSettingsRules.validate(o, AuthSettings.resolve(DEFAULTS, o)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKeys(
                        "passwordMinLength", "sessionIdleTimeoutMinutes", "sessionAbsoluteTimeoutMinutes",
                        "accessTokenTtlCapMinutes", "refreshTokenTtlCapMinutes"));
    }

    @Test
    void theIdleTimeoutCannotOutlastTheAbsoluteTimeout() {
        AuthSettingsOverrides o = with(null, 600, 120, null, null);
        assertThatThrownBy(() -> AuthSettingsRules.validate(o, AuthSettings.resolve(DEFAULTS, o)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKey("sessionIdleTimeoutMinutes"));
    }

    @Test
    void tokenLifetimeSettingsAreCeilingsOnTheClientsOwnLifetime() {
        AuthSettings s = AuthSettings.resolve(DEFAULTS, with(null, null, null, 10, 60));
        assertThat(s.capAccess(Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(10));
        assertThat(s.capAccess(Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(5));
        assertThat(s.capRefresh(Duration.ofDays(30))).isEqualTo(Duration.ofHours(1));
        assertThat(DEFAULTS.capAccess(Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void thePasswordPolicyReadsTheMinimumAtEveryCheck() {
        int[] min = {12};
        PasswordPolicy policy = new DefaultPasswordPolicy(() -> min[0]);
        assertThat(policy.violations("Abcdefgh1jk", List.of())).anyMatch(v -> v.contains("12"));
        min[0] = 8;
        assertThat(policy.violations("Abcdefgh1jk", List.of())).isEmpty();
    }

    @Test
    void mfaIsMandatoryForStaffAndWhereAPortalSwitchIsOnAndAFactorCanBeAsked() {
        AuthSettings sellersOn = new AuthSettings(12, false, true, false, 30, 1440, null, null);
        MfaEnrolment none = new MfaEnrolment(false, false, null, 0, 0);
        MfaEnrolment totp = new MfaEnrolment(true, false, null, 0, 0);

        assertThat(MfaRequirement.mandatory(true, Set.of("platform.finance"), DEFAULTS, none, false)).isTrue();
        assertThat(MfaRequirement.mandatory(false, Set.of("seller.owner"), sellersOn, none, true)).isTrue();
        assertThat(MfaRequirement.mandatory(false, Set.of("seller.owner"), sellersOn, totp, false)).isTrue();
        // nothing to ask: no enrolled factor and no phone on file, so the switch must not lock the person out
        assertThat(MfaRequirement.mandatory(false, Set.of("seller.owner"), sellersOn, none, false)).isFalse();
        // other portals are unaffected by the seller switch
        assertThat(MfaRequirement.mandatory(false, Set.of("account.customer"), sellersOn, totp, true)).isFalse();
        assertThat(MfaRequirement.mandatory(false, Set.of("provider.manager"), sellersOn, totp, true)).isFalse();
    }
}
