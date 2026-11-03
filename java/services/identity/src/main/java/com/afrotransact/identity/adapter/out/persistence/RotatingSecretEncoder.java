package com.afrotransact.identity.adapter.out.persistence;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Checks a client secret against a rotated pair: {@code {rotating}<previousValidUntil epoch seconds>|<new hash>|<old
 * hash>}, each hash with its own {@code {id}} prefix. The new secret always matches; the old one only until the grace
 * period ends. Registered as the {@code rotating} id of the delegating password encoder, so
 * {@link GovernedRegisteredClientRepository} can hand the authorization server such a value without storing it.
 */
public class RotatingSecretEncoder implements PasswordEncoder {

    public static final String ID = "rotating";

    private final PasswordEncoder hashes;
    private final Clock clock;

    /** @param hashes the encoder that understands the individual hashes (the delegating encoder without this id) */
    public RotatingSecretEncoder(PasswordEncoder hashes, Clock clock) {
        this.hashes = hashes;
        this.clock = clock;
    }

    /** The value the delegating encoder receives for a client in rotation (prefix included). */
    public static String compose(Instant previousValidUntil, String currentHash, String previousHash) {
        return "{" + ID + "}" + previousValidUntil.getEpochSecond() + "|" + currentHash + "|" + previousHash;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        throw new UnsupportedOperationException("A rotation pair is composed, never encoded");
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encoded) {
        String[] parts = encoded.split("\\|", 3);
        if (parts.length != 3) {
            return false;
        }
        if (hashes.matches(rawPassword, parts[1])) {
            return true;
        }
        try {
            long until = Long.parseLong(parts[0]);
            return Instant.now(clock).getEpochSecond() < until && hashes.matches(rawPassword, parts[2]);
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    @Override
    public boolean upgradeEncoding(String encoded) {
        return false;
    }
}
