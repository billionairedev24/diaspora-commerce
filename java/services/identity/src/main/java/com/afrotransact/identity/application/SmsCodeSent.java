package com.afrotransact.identity.application;

import java.time.Instant;

/** @param sentTo masked destination such as {@code •••• 0142} */
public record SmsCodeSent(String sentTo, Instant expiresAt) {
}
