package com.afrotransact.taxreturns.domain.profile;

import java.time.Instant;
import java.util.Map;

/** One append-only audit row of a classification record. */
public record AuditEntry(Instant at, String actor, AuditAction action, String reason, String ruleVersion,
                         Map<String, Object> before, Map<String, Object> after) {
}
