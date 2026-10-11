package com.afrotransact.identity.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import com.afrotransact.identity.application.TestUserSettings;
import com.afrotransact.identity.domain.DefaultLocalDevelopmentPolicy;
import com.afrotransact.identity.domain.LocalDevelopmentPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * S276 guard rails for developer conveniences: the local test accounts and code delivery to Mailpit. Both are decided
 * here from the active Spring profiles, and a configuration that asks for them on uat or prod stops the service at
 * start-up instead of quietly running with them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalDevelopmentProperties.class)
class LocalDevelopmentConfiguration {

    private static final String DEV = "dev";

    @Bean
    LocalDevelopmentPolicy localDevelopmentPolicy() {
        return new DefaultLocalDevelopmentPolicy();
    }

    @Bean
    TestUserSettings testUserSettings(LocalDevelopmentPolicy policy, LocalDevelopmentProperties props,
                                      Environment env) {
        return testUserSettings(policy, activeProfiles(env), props);
    }

    /** Active profiles, or the default profile ({@code local}) when none is active. */
    static Set<String> activeProfiles(Environment env) {
        String[] active = env.getActiveProfiles();
        return new LinkedHashSet<>(Arrays.asList(active.length > 0 ? active : env.getDefaultProfiles()));
    }

    /**
     * Off unless asked for. Asked for on a profile that may not have them (uat, prod, anything but local or dev):
     * refuses to start. On dev the well-known local password is refused: dev must set its own.
     */
    static TestUserSettings testUserSettings(LocalDevelopmentPolicy policy, Set<String> profiles,
                                             LocalDevelopmentProperties props) {
        if (!props.testUsers()) {
            return TestUserSettings.off();
        }
        if (!policy.testUsersAllowed(profiles, true)) {
            throw new IllegalStateException("at.identity.local-development.test-users is on, but test users exist "
                    + "only on the local profile (or dev): active profiles " + profiles);
        }
        boolean local = profiles.contains("local");
        String password = props.testUsersPassword();
        if (!local && profiles.contains(DEV)
                && (password == null || LocalDevelopmentPolicy.LOCAL_TEST_PASSWORD.equals(password))) {
            throw new IllegalStateException("Test users on dev need their own password "
                    + "(IDENTITY_TEST_USERS_PASSWORD), not the published local one");
        }
        return new TestUserSettings(true, password == null ? LocalDevelopmentPolicy.LOCAL_TEST_PASSWORD : password);
    }

    /** The configured code sender, or start-up fails when it may not run on these profiles. */
    static String checkedSender(LocalDevelopmentPolicy policy, String sender, Set<String> profiles) {
        if (!policy.verificationSenderAllowed(sender, profiles)) {
            throw new IllegalStateException("at.identity.verification.sender=" + sender + " is for the local profile "
                    + "only (active profiles " + profiles + "); use outbox");
        }
        return sender == null || sender.isBlank() ? "outbox" : sender.trim();
    }
}
