package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only access change log entry.
 *
 * @param action  for example {@code role.granted}, {@code permission.revoked}, {@code user.disabled}
 * @param subject what changed: role key, permission key, module, provider…
 */
public record AccessChange(long id, Instant at, String actorId, String action, UUID targetUserId, String targetRole,
                           String subject, String detail) {
}
