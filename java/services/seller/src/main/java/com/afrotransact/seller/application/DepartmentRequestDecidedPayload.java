package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of seller.department_request.decided: who decided which department request of which organisation, how and
 * why (trust-ops copies it into the audit log).
 *
 * @param decision APPROVED or REJECTED
 * @param actorId  the staff member
 * @param reason   the decision note (required for a rejection)
 */
public record DepartmentRequestDecidedPayload(UUID requestId, String orgId, String departmentCode, String decision,
                                              String actorId, String reason, Instant occurredAt) {
}
