package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** S276: test users and non-outbox code delivery are local conveniences; uat and prod never get them. */
class DefaultLocalDevelopmentPolicyTest {

    private final LocalDevelopmentPolicy policy = new DefaultLocalDevelopmentPolicy();

    @Test
    void testUsersOnLocalAndOnDevOnlyWithTheFlag() {
        assertThat(policy.testUsersAllowed(Set.of("local"), false)).isTrue();
        assertThat(policy.testUsersAllowed(Set.of("LOCAL"), false)).isTrue();
        assertThat(policy.testUsersAllowed(Set.of("dev"), true)).isTrue();
        assertThat(policy.testUsersAllowed(Set.of("dev"), false)).isFalse();
    }

    @Test
    void neverOnUatOrProdWhateverElseIsActive() {
        for (String env : new String[] {"uat", "prod", "Prod"}) {
            assertThat(policy.testUsersAllowed(Set.of(env), true)).as(env).isFalse();
            assertThat(policy.testUsersAllowed(Set.of("local", env), true)).as("local + " + env).isFalse();
            assertThat(policy.testUsersAllowed(Set.of("dev", env), true)).as("dev + " + env).isFalse();
        }
        assertThat(policy.testUsersAllowed(Set.of(), true)).isFalse();
        assertThat(policy.testUsersAllowed(null, true)).isFalse();
        assertThat(policy.testUsersAllowed(Set.of("staging"), true)).isFalse();
    }

    @Test
    void codesLeaveThroughTheOutboxEverywhereAndThroughLogOrMailpitOnlyLocally() {
        for (String env : new String[] {"local", "dev", "uat", "prod"}) {
            assertThat(policy.verificationSenderAllowed("outbox", Set.of(env))).as(env).isTrue();
            assertThat(policy.verificationSenderAllowed(null, Set.of(env))).as(env).isTrue();
        }
        assertThat(policy.verificationSenderAllowed("mailpit", Set.of("local"))).isTrue();
        assertThat(policy.verificationSenderAllowed("log", Set.of("local"))).isTrue();
        for (String env : new String[] {"dev", "uat", "prod"}) {
            assertThat(policy.verificationSenderAllowed("mailpit", Set.of(env))).as(env).isFalse();
            assertThat(policy.verificationSenderAllowed("log", Set.of(env))).as(env).isFalse();
            assertThat(policy.verificationSenderAllowed("mailpit", Set.of("local", env))).as("local + " + env)
                    .isFalse();
        }
    }

    @Test
    void bootstrapAdminOnUatAndProdNeedsARealAddressAndALongOwnPassword() {
        String strong = "correct-horse-battery-staple";
        for (String env : new String[] {"uat", "prod", "Prod"}) {
            assertThat(policy.bootstrapAdminAllowed(Set.of(env), "ops@afrotransact.com", strong)).as(env).isTrue();
            assertThat(policy.bootstrapAdminAllowed(Set.of(env), "ops@afrotransact.com", "LocalAdmin2026!")).as(env)
                    .isFalse();
            assertThat(policy.bootstrapAdminAllowed(Set.of(env), "ops@afrotransact.com", "Test1234!Local")).as(env)
                    .isFalse();
            assertThat(policy.bootstrapAdminAllowed(Set.of(env), "ops@afrotransact.com", "short-pass1!")).as(env)
                    .isFalse();
            assertThat(policy.bootstrapAdminAllowed(Set.of(env), "admin@afrotransact.test", strong)).as(env).isFalse();
            assertThat(policy.bootstrapAdminAllowed(Set.of("local", env), "ops@afrotransact.com", "LocalAdmin2026!"))
                    .as("local + " + env).isFalse();
        }
        assertThat(policy.bootstrapAdminAllowed(Set.of("prod"), "ops@afrotransact.com", null)).isFalse();
    }

    @Test
    void bootstrapAdminOnDevMayNotUseThePublishedPasswordsAndLocalIsUnrestricted() {
        assertThat(policy.bootstrapAdminAllowed(Set.of("dev"), "admin@afrotransact.com", "LocalAdmin2026!")).isFalse();
        assertThat(policy.bootstrapAdminAllowed(Set.of("dev"), "admin@afrotransact.com", "a-dev-secret-1")).isTrue();
        assertThat(policy.bootstrapAdminAllowed(Set.of("local"), "admin@afrotransact.com", "LocalAdmin2026!")).isTrue();
    }

    @Test
    void testUserEmailsUseTheReservedDomainOnly() {
        assertThat(policy.isTestUserEmail("customer.houston@afrotransact.test")).isTrue();
        assertThat(policy.isTestUserEmail(" Staff.Admin@AfroTransact.TEST ")).isTrue();
        assertThat(policy.isTestUserEmail("admin@afrotransact.com")).isFalse();
        assertThat(policy.isTestUserEmail("x@afrotransact.test.example.com")).isFalse();
        assertThat(policy.isTestUserEmail("x@evil-afrotransact.test")).isFalse();
        assertThat(policy.isTestUserEmail(null)).isFalse();
    }
}
