package com.afrotransact.identity.adapter.out.crypto;

import com.afrotransact.identity.port.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Delegates to the application's {@link PasswordEncoder} ({@code {bcrypt}} by default, upgradeable). */
@Component
class SpringPasswordHasher implements PasswordHasher {

    private final PasswordEncoder encoder;

    SpringPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String hash(String raw) {
        return encoder.encode(raw);
    }

    @Override
    public boolean matches(String raw, String hash) {
        return hash != null && encoder.matches(raw, hash);
    }
}
