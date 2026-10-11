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

    /** True for an address under {@link #TEST_USER_DOMAIN}. */
    boolean isTestUserEmail(String email);
}
