package com.afrotransact.identity.application;

/** Authenticator-app enrolment: the client renders {@code otpauthUri} as a QR code. */
public record TotpSetup(String secret, String otpauthUri) {
}
