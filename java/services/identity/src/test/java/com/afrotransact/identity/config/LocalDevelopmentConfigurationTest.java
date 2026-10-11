package com.afrotransact.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.afrotransact.identity.application.TestUserSettings;
import com.afrotransact.identity.domain.DefaultLocalDevelopmentPolicy;
import com.afrotransact.identity.domain.LocalDevelopmentPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * S276: what start-up decides from the profiles. Test users and code delivery to Mailpit or the log are refused on
 * uat and prod even when configured (the service does not start), and dev needs an explicit flag and its own password.
 */
class LocalDevelopmentConfigurationTest {

    private final LocalDevelopmentPolicy policy = new DefaultLocalDevelopmentPolicy();

    private static LocalDevelopmentProperties on(String password) {
        return new LocalDevelopmentProperties(true, password, null);
    }

    @Test
    void localGetsTheTestUsersWithTheSharedPassword() {
        TestUserSettings s = LocalDevelopmentConfiguration.testUserSettings(policy, Set.of("local"), on(null));
        assertThat(s.enabled()).isTrue();
        assertThat(s.password()).isEqualTo("Test1234!Local");
        assertThat(s.toString()).doesNotContain("Test1234");
    }

    @Test
    void offUnlessAskedFor() {
        for (String env : new String[] {"local", "dev", "uat", "prod"}) {
            assertThat(LocalDevelopmentConfiguration.testUserSettings(policy, Set.of(env),
                    new LocalDevelopmentProperties(null, null, null)).enabled()).as(env).isFalse();
        }
    }

    @Test
    void uatAndProdRefuseToStartWhenTestUsersAreConfigured() {
        for (String env : new String[] {"uat", "prod"}) {
            assertThatThrownBy(() -> LocalDevelopmentConfiguration.testUserSettings(policy, Set.of(env),
                    on("Another-Pass-2026")))
                    .as(env).isInstanceOf(IllegalStateException.class).hasMessageContaining("only on the local");
            assertThatThrownBy(() -> LocalDevelopmentConfiguration.testUserSettings(policy, Set.of("local", env),
                    on(null)))
                    .as("local + " + env).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void devNeedsItsOwnPassword() {
        assertThatThrownBy(() -> LocalDevelopmentConfiguration.testUserSettings(policy, Set.of("dev"), on(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("IDENTITY_TEST_USERS_PASSWORD");
        assertThatThrownBy(() -> LocalDevelopmentConfiguration.testUserSettings(policy, Set.of("dev"),
                on("Test1234!Local"))).isInstanceOf(IllegalStateException.class);
        TestUserSettings dev = LocalDevelopmentConfiguration.testUserSettings(policy, Set.of("dev"),
                on("Dev-Only-Secret-2026"));
        assertThat(dev.enabled()).isTrue();
        assertThat(dev.password()).isEqualTo("Dev-Only-Secret-2026");
    }

    @Test
    void mailpitAndLogSendersRefuseToStartOutsideLocal() {
        assertThat(LocalDevelopmentConfiguration.checkedSender(policy, "mailpit", Set.of("local"))).isEqualTo("mailpit");
        assertThat(LocalDevelopmentConfiguration.checkedSender(policy, null, Set.of("prod"))).isEqualTo("outbox");
        assertThat(LocalDevelopmentConfiguration.checkedSender(policy, "outbox", Set.of("prod"))).isEqualTo("outbox");
        for (String env : new String[] {"dev", "uat", "prod"}) {
            for (String sender : new String[] {"mailpit", "log"}) {
                assertThatThrownBy(() -> LocalDevelopmentConfiguration.checkedSender(policy, sender, Set.of(env)))
                        .as(sender + " on " + env).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("local profile only");
            }
        }
    }

    @Test
    void activeProfilesFallBackToTheDefaultProfile() {
        MockEnvironment none = new MockEnvironment();
        none.setDefaultProfiles("local");
        assertThat(LocalDevelopmentConfiguration.activeProfiles(none)).containsExactly("local");
        MockEnvironment prod = new MockEnvironment();
        prod.setDefaultProfiles("local");
        prod.setActiveProfiles("prod");
        assertThat(LocalDevelopmentConfiguration.activeProfiles(prod)).containsExactly("prod");
    }
}
