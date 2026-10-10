package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.UserAccount;

/** Everything the token customizer writes into access and ID tokens for a user. */
public record TokenClaims(UUID userId, UserAccount user, EffectiveAccess access) {
}
