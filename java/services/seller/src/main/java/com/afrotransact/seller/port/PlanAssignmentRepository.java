package com.afrotransact.seller.port;

import java.util.List;

import com.afrotransact.seller.domain.PlanAssignment;

/** The record of plans staff assigned. */
public interface PlanAssignmentRepository {

    void insert(PlanAssignment assignment);

    /** Newest first. */
    List<PlanAssignment> ofOrg(String orgId, int limit);
}
