package com.afrotransact.identity.application;

import java.time.Instant;

/** @param sentTo masked destination such as {@code a••••@example.com} */
public record EmailCodeSent(String sentTo, Instant expiresAt) {
}
