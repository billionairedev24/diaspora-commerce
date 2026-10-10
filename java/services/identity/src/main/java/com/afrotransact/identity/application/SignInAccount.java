package com.afrotransact.identity.application;

import java.util.UUID;

/** What the login form needs to authenticate a user. */
public record SignInAccount(UUID id, String email, String passwordHash, boolean enabled, boolean emailVerified) {
}
