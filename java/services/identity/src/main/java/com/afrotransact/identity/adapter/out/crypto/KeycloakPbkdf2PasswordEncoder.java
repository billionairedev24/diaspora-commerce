package com.afrotransact.identity.adapter.out.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies password hashes imported from Keycloak so migrated accounts keep signing in with the password they have.
 * <p>
 * The stored form is {@code iterations$base64(salt)$base64(derivedKey)}, which is Keycloak's credential
 * {@code credential_data.hashIterations}, {@code secret_data.salt} and {@code secret_data.value} joined together;
 * Spring's delegating encoder adds the {@code {kc-pbkdf2-sha256}} style prefix that selects the algorithm.
 * Verification only: new passwords are always hashed with the application's default encoder (BCrypt), so a user
 * who changes or resets their password leaves the Keycloak format behind.
 */
public final class KeycloakPbkdf2PasswordEncoder implements PasswordEncoder {

    /** The Keycloak algorithm names {@code pbkdf2}, {@code pbkdf2-sha256} and {@code pbkdf2-sha512}. */
    public enum Variant {
        SHA1("PBKDF2WithHmacSHA1", 160),
        SHA256("PBKDF2WithHmacSHA256", 256),
        SHA512("PBKDF2WithHmacSHA512", 512);

        private final String jcaName;
        private final int keyBits;

        Variant(String jcaName, int keyBits) {
            this.jcaName = jcaName;
            this.keyBits = keyBits;
        }

        /** The delegating-encoder id for this variant, e.g. {@code kc-pbkdf2-sha256}. */
        public String id() {
            return "kc-pbkdf2-" + name().toLowerCase();
        }
    }

    private static final int MAX_ITERATIONS = 1_000_000;

    private final Variant variant;

    public KeycloakPbkdf2PasswordEncoder(Variant variant) {
        this.variant = variant;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        throw new UnsupportedOperationException("Keycloak hashes are verified, never produced");
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$", -1);
        if (parts.length != 3) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[0]);
            if (iterations < 1 || iterations > MAX_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toString().toCharArray(), salt, iterations, variant.keyBits);
            byte[] actual = SecretKeyFactory.getInstance(variant.jcaName).generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    /** Hash text for a Keycloak credential, as the importer writes it into {@code user_account.password_hash}. */
    public static String stored(Variant variant, int iterations, byte[] salt, byte[] derivedKey) {
        Base64.Encoder b64 = Base64.getEncoder();
        return "{" + variant.id() + "}" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derivedKey);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return false;
    }
}
