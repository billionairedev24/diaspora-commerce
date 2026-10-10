package com.afrotransact.identity.port;

/** One-way password hashing (BCrypt via Spring Security's delegating encoder). */
public interface PasswordHasher {

    String hash(String raw);

    boolean matches(String raw, String hash);
}
