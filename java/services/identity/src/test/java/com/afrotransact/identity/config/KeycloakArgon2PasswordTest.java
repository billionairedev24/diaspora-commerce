package com.afrotransact.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Keycloak 26 hashes passwords with argon2id, so an account imported from it carries an argon2 hash and signs in
 * with the password its owner already has. Unlike Keycloak's PBKDF2 layout, argon2 has a standard encoded form, so
 * identity verifies it with Spring's stock encoder instead of one of ours.
 */
class KeycloakArgon2PasswordTest {

    /*
     * Not a hand-made vector: Keycloak 26.6.4 produced it. The password below was set through the admin API, then
     * the credential row was read straight out of the keycloak schema:
     *   secret_data     {"value":"EJyK/VFDBVobHWNFiD0+oqtffNpvw6jqJx1eaHaisvc=","salt":"wf32khe/F9jW8dyxvH88kg=="}
     *   credential_data {"hashIterations":5,"algorithm":"argon2",
     *                    "additionalParameters":{"memory":["7168"],"type":["id"],"version":["1.3"],"parallelism":["1"]}}
     * tools/migrate/keycloak_to_identity.py assembles exactly the string below from those two columns.
     */
    private static final String PASSWORD = "Correct-Horse-Battery-9";
    private static final String IMPORTED =
            "{argon2}$argon2id$v=19$m=7168,t=5,p=1$wf32khe/F9jW8dyxvH88kg$EJyK/VFDBVobHWNFiD0+oqtffNpvw6jqJx1eaHaisvc";

    private final PasswordEncoder encoder = new IdentityConfiguration().passwordEncoder(Clock.systemUTC());

    @Test
    void verifiesAPasswordKeycloakHashedWithArgon2() {
        assertThat(encoder.matches(PASSWORD, IMPORTED)).isTrue();
    }

    @Test
    void rejectsTheWrongPassword() {
        assertThat(encoder.matches("Correct-Horse-Battery-8", IMPORTED)).isFalse();
    }

    @Test
    void newPasswordsAreStillHashedWithBcrypt() {
        assertThat(encoder.encode(PASSWORD)).startsWith("{bcrypt}");
    }
}
