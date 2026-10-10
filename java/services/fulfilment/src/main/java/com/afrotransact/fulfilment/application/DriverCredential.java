package com.afrotransact.fulfilment.application;

import java.time.Instant;

/**
 * The driver app's access token as received on this request.
 *
 * @param bearer   raw token, relayed to orders-service for courier events during a live sync (never stored)
 * @param issuedAt token issue time ({@code iat}); a token issued before a revocation is told to sign out
 */
public record DriverCredential(String bearer, Instant issuedAt) {
}
