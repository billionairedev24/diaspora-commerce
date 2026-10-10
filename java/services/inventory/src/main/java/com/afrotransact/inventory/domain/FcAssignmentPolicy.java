package com.afrotransact.inventory.domain;

import java.util.List;

/** Chooses the destination fulfilment centre of an FBA inbound plan (S257). */
public interface FcAssignmentPolicy {

    /**
     * @param requestedNodeId the FC the seller asked for, or null
     * @param requiredZone    storage zone the plan needs (e.g. Chilled), or null
     * @throws InventoryRuleViolation when no FC can take the plan
     */
    FcAssignment assign(List<FcCandidate> candidates, String requestedNodeId, String requiredZone);
}
