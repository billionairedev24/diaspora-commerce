package com.afrotransact.identity.domain;

import java.util.Set;

/**
 * Which developer conveniences an environment may use (S276): ready-made test accounts and one-time codes delivered
 * somewhere other than the notifications service. They exist for the {@code local} profile; {@code dev} may opt in to
 * test accounts explicitly; {@code uat} and {@code prod} never get either, whatever the configuration says.
 */
public interface LocalDevelopmentPolicy {

    /** Test accounts live under this reserved domain only (RFC 2606 {@code .test}: never a real mailbox). */
    String TEST_USER_DOMAIN = "afrotransact.test";

    /** The shared password the local profile gives every test account. */
    String LOCAL_TEST_PASSWORD = "Test1234!Local";

    /**
     * @param activeProfiles Spring profiles in effect (the default profile when none is active)
     * @param devOptIn       the explicit flag that allows test accounts on {@code dev}
     * @return true for {@code local}, or {@code dev} with the flag; false whenever {@code uat} or {@code prod} is active
     */
    boolean testUsersAllowed(Set<String> activeProfiles, boolean devOptIn);

    /**
     * Code delivery: {@code outbox} (the notifications service) everywhere; {@code log} and {@code mailpit} (straight
     * to the local Mailpit inbox) only on {@code local}.
     */
    boolean verificationSenderAllowed(String sender, Set<String> activeProfiles);

    /** The password the local profile gives the bootstrap super administrator. */
    String LOCAL_ADMIN_PASSWORD = "LocalAdmin2026!";

    /**
     * Whether the bootstrap super administrator may be created with these credentials. On {@code uat} and {@code prod}
     * (and {@code dev} for the published local passwords) the account must not use a made-up {@code .test} address, a
     * password published in this repository, or a password shorter than {@link #MIN_ADMIN_PASSWORD_LENGTH}; the
     * {@code local} profile has no restriction.
     */
    boolean bootstrapAdminAllowed(Set<String> activeProfiles, String email, String password);

    /** Shortest bootstrap administrator password accepted on uat and prod. */
    int MIN_ADMIN_PASSWORD_LENGTH = 16;

    /** True for an address under {@link #TEST_USER_DOMAIN}. */
    boolean isTestUserEmail(String email);
}
