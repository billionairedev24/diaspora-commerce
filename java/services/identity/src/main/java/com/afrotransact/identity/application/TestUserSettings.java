package com.afrotransact.identity.application;

/**
 * Whether this environment provisions the local test accounts (S276), decided at start-up by the domain's
 * LocalDevelopmentPolicy from the active profiles: never on uat or prod.
 *
 * @param enabled  test accounts may be created and repaired
 * @param password their shared password (null when disabled)
 */
public record TestUserSettings(boolean enabled, String password) {

    public static TestUserSettings off() {
        return new TestUserSettings(false, null);
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "TestUserSettings[enabled=" + enabled + "]";
    }
}
