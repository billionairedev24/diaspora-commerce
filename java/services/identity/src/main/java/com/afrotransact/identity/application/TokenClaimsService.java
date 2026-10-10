package com.afrotransact.identity.application;

import java.util.Optional;

/**
 * Computes {@code pt}, {@code org}, {@code roles} and {@code perms} at token issuance (including refresh), so a
 * revoked permission disappears from the next token (access tokens live 5 minutes).
 */
public interface TokenClaimsService {

    /** Empty when {@code subject} is not a user (for example a client-credentials client id). */
    Optional<TokenClaims> forSubject(String subject);
}
