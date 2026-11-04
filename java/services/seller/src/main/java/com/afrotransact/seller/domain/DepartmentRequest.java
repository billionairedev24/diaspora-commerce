package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A seller's request to also sell in a department. Staff approve (the department joins the organisation's set) or
 * reject with a note.
 *
 * @param needs what the seller still has to provide for the department ({@link Department.Requirement})
 */
public record DepartmentRequest(UUID id, String orgId, String departmentCode, String note, DepartmentRequestStatus status,
                                String requestedBy, Instant createdAt, String decidedBy, Instant decidedAt,
                                String decisionNote, Set<Department.Requirement> needs) {

    public DepartmentRequest {
        needs = needs == null ? Set.of() : Set.copyOf(needs);
    }

    public DepartmentRequest decided(DepartmentRequestStatus next, String by, String decisionNote, Instant at) {
        return new DepartmentRequest(id, orgId, departmentCode, note, next, requestedBy, createdAt, by, at,
                decisionNote, needs);
    }

    public DepartmentRequest withNeeds(Set<Department.Requirement> next) {
        return new DepartmentRequest(id, orgId, departmentCode, note, status, requestedBy, createdAt, decidedBy,
                decidedAt, this.decisionNote, next);
    }
}
