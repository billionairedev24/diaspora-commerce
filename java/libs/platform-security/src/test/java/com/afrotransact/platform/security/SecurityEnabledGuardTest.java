package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class SecurityEnabledGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"uat", "prod", "dev", "staging", "PROD"})
    void securityOffRefusesToStartInAnyNonLocalProfile(String profile) {
        SecurityEnabledGuard guard = new SecurityEnabledGuard(false, new String[] {profile}, null);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at.security.enabled=false")
                .hasMessageContaining(profile.toLowerCase());
    }

    @Test
    void securityOffRefusesWhenAppEnvIsProdEvenWithoutAProfile() {
        SecurityEnabledGuard guard = new SecurityEnabledGuard(false, new String[0], "prod");

        assertThatThrownBy(guard::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oneNonLocalProfileAmongLocalOnesStillRefuses() {
        SecurityEnabledGuard guard = new SecurityEnabledGuard(false, new String[] {"local", "uat"}, null);

        assertThatThrownBy(guard::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void securityOffIsAllowedLocallyAndInTests() {
        assertThatCode(new SecurityEnabledGuard(false, new String[] {"local"}, "local")::afterPropertiesSet)
                .doesNotThrowAnyException();
        assertThatCode(new SecurityEnabledGuard(false, new String[] {"test"}, null)::afterPropertiesSet)
                .doesNotThrowAnyException();
        assertThatCode(new SecurityEnabledGuard(false, new String[0], null)::afterPropertiesSet)
                .doesNotThrowAnyException();
    }

    @Test
    void securityOnNeverRefuses() {
        assertThatCode(new SecurityEnabledGuard(true, new String[] {"prod"}, "prod")::afterPropertiesSet)
                .doesNotThrowAnyException();
    }

    @Test
    void readsTheProfileAndAppEnvFromTheEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty("APP_ENV", "uat");
        PlatformSecurityProperties off = new PlatformSecurityProperties(false, null, null, null);

        assertThatThrownBy(new SecurityEnabledGuard(off, env)::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class);
    }
}
