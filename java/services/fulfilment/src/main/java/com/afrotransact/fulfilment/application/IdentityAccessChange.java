package com.afrotransact.fulfilment.application;

import java.time.Instant;

/**
 * An access change from identity-service ({@code at.identity.events}): role granted/revoked, permission
 * granted/revoked, user disabled/enabled.
 */
public record IdentityAccessChange(String type, String userId, String roleKey, String permissionKey, String reason,
                                   Instant at) {
}
