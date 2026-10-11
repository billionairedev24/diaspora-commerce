package com.afrotransact.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.identity.local-development.*} (S276): developer conveniences, refused outside local (and dev for test
 * users) by {@link LocalDevelopmentConfiguration} whatever these values say.
 *
 * @param testUsers         provision the local test accounts (application-local.yml: on; dev: IDENTITY_TEST_USERS)
 * @param testUsersPassword their shared password; local defaults to {@code Test1234!Local}, dev must set its own
 * @param mailpitUrl        Mailpit web/API address for {@code at.identity.verification.sender=mailpit}
 */
@ConfigurationProperties(prefix = "at.identity.local-development")
public record LocalDevelopmentProperties(Boolean testUsers, String testUsersPassword, String mailpitUrl) {

    public LocalDevelopmentProperties {
        testUsers = testUsers != null && testUsers;
        testUsersPassword = testUsersPassword == null || testUsersPassword.isBlank() ? null : testUsersPassword;
        mailpitUrl = mailpitUrl == null || mailpitUrl.isBlank() ? "http://localhost:8025" : mailpitUrl.trim();
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "LocalDevelopmentProperties[testUsers=" + testUsers + ", mailpitUrl=" + mailpitUrl + "]";
    }
}
