package com.afrotransact.identity.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afrotransact.identity.adapter.out.crypto.KeycloakPbkdf2PasswordEncoder.Variant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class KeycloakPbkdf2PasswordEncoderTest {

    /*
     * Reference vectors made with Python's hashlib.pbkdf2_hmac (an implementation independent of the JDK) the way
     * Keycloak derives them: password as UTF-8, the raw salt bytes, the credential's iteration count.
     *   pbkdf2_hmac('sha256', b'correct horse', base64.b64decode('c2FsdHNhbHRzYWx0MTI='), 27500)
     *   pbkdf2_hmac('sha512', 'pässword'.encode(), base64.b64decode('c2FsdHNhbHRzYWx0MTI='), 210000)
     *   pbkdf2_hmac('sha1',   b'old-account', base64.b64decode('c2FsdHNhbHRzYWx0MTI='), 20000)
     */
    private static final String SHA256 = "27500$c2FsdHNhbHRzYWx0MTI=$" + "otiK+act+Syw+0MUc+CGgxy4WdHfzS5udQfJratLvyY=";
    private static final String SHA512 = "210000$c2FsdHNhbHRzYWx0MTI=$" + "inix+l6w3yk7YuuqiWxxxgK5S1BLOk9D2q8docss2PchZP8nD3oGk5bTfo1NMEi1eh9DkKKBn8zQKzuFYPW0cw==";
    private static final String SHA1 = "20000$c2FsdHNhbHRzYWx0MTI=$" + "F2T80ZvsPuoo8Y34B9J2yzFIW5E=";

    private static PasswordEncoder delegating() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        for (Variant v : Variant.values()) {
            encoders.put(v.id(), new KeycloakPbkdf2PasswordEncoder(v));
        }
        return new DelegatingPasswordEncoder("bcrypt", encoders);
    }

    @Test
    void verifiesKeycloakSha256() {
        KeycloakPbkdf2PasswordEncoder e = new KeycloakPbkdf2PasswordEncoder(Variant.SHA256);
        assertThat(e.matches("correct horse", SHA256)).isTrue();
        assertThat(e.matches("correct horsf", SHA256)).isFalse();
    }

    @Test
    void verifiesKeycloakSha512WithNonAsciiPassword() {
        KeycloakPbkdf2PasswordEncoder e = new KeycloakPbkdf2PasswordEncoder(Variant.SHA512);
        assertThat(e.matches("pässword", SHA512)).isTrue();
        assertThat(e.matches("password", SHA512)).isFalse();
    }

    @Test
    void verifiesLegacyKeycloakSha1() {
        assertThat(new KeycloakPbkdf2PasswordEncoder(Variant.SHA1).matches("old-account", SHA1)).isTrue();
    }

    @Test
    void rejectsMalformedHashesWithoutThrowing() {
        KeycloakPbkdf2PasswordEncoder e = new KeycloakPbkdf2PasswordEncoder(Variant.SHA256);
        assertThat(e.matches("x", "not-a-hash")).isFalse();
        assertThat(e.matches("x", "0$c2FsdA==$AAAA")).isFalse();
        assertThat(e.matches("x", "27500$***$AAAA")).isFalse();
        assertThat(e.matches("x", "99999999$c2FsdA==$AAAA")).isFalse();
        assertThat(e.matches("x", null)).isFalse();
    }

    @Test
    void neverProducesKeycloakHashes() {
        assertThatThrownBy(() -> new KeycloakPbkdf2PasswordEncoder(Variant.SHA256).encode("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void delegatingEncoderPicksTheKeycloakVariantFromThePrefixAndStillHashesWithBcrypt() {
        PasswordEncoder d = delegating();
        assertThat(d.matches("correct horse", "{kc-pbkdf2-sha256}" + SHA256)).isTrue();
        assertThat(d.matches("pässword", "{kc-pbkdf2-sha512}" + SHA512)).isTrue();
        assertThat(d.matches("correct horse", "{kc-pbkdf2-sha512}" + SHA256)).isFalse();
        String fresh = d.encode("new password");
        assertThat(fresh).startsWith("{bcrypt}");
        assertThat(d.matches("new password", fresh)).isTrue();
    }

    @Test
    void storedFormRoundTripsThroughTheEncoder() {
        byte[] salt = Base64.getDecoder().decode("c2FsdHNhbHRzYWx0MTI=");
        byte[] key = Base64.getDecoder().decode(SHA256.substring(SHA256.lastIndexOf('$') + 1));
        String stored = KeycloakPbkdf2PasswordEncoder.stored(Variant.SHA256, 27500, salt, key);
        assertThat(stored).isEqualTo("{kc-pbkdf2-sha256}" + SHA256);
    }
}
