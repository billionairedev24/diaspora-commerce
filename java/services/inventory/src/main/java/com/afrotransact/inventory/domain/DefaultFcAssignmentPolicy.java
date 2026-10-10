package com.afrotransact.inventory.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FCs where another organisation stocks one of the plan's SKUs are never chosen (a position holds one owner's units).
 * A requested FC wins when it qualifies; otherwise the FC already holding most of the seller's plan SKUs, then the
 * least loaded one, then the node id (deterministic).
 */
public class DefaultFcAssignmentPolicy implements FcAssignmentPolicy {

    @Override
    public FcAssignment assign(List<FcCandidate> candidates, String requestedNodeId, String requiredZone) {
        List<FcCandidate> eligible = candidates.stream()
                .filter(c -> c.foreignSkus().isEmpty())
                .filter(c -> requiredZone == null || c.temperatureZones().stream()
                        .anyMatch(z -> z.equalsIgnoreCase(requiredZone)))
                .toList();
        if (requestedNodeId != null) {
            Optional<FcCandidate> requested = candidates.stream().filter(c -> c.nodeId().equals(requestedNodeId))
                    .findFirst();
            if (requested.isEmpty()) {
                throw new InventoryRuleViolation("inventory.fc-unavailable", "No active fulfilment centre "
                        + requestedNodeId, Map.of("nodeId", "Not an active fulfilment centre"));
            }
            if (eligible.contains(requested.get())) {
                return new FcAssignment(requestedNodeId, "Requested by the seller");
            }
            if (!requested.get().foreignSkus().isEmpty()) {
                throw new InventoryRuleViolation("inventory.fc-unavailable", requestedNodeId + " already holds "
                        + String.join(", ", requested.get().foreignSkus()) + " of another seller; units are never "
                        + "mixed. Leave the FC empty to get one assigned", Map.of("nodeId", "SKU owned by another "
                        + "seller at this FC"));
            }
            throw new InventoryRuleViolation("inventory.fc-unavailable", requestedNodeId + " has no " + requiredZone
                    + " storage", Map.of("nodeId", "No " + requiredZone + " storage"));
        }
        return eligible.stream()
                .min(Comparator.comparingInt(FcCandidate::ownStockedSkus).reversed()
                        .thenComparingLong(FcCandidate::unitsHeld)
                        .thenComparing(FcCandidate::nodeId))
                .map(c -> new FcAssignment(c.nodeId(), c.ownStockedSkus() > 0
                        ? "Already holds " + c.ownStockedSkus() + " of these SKUs for you"
                        : "Least loaded fulfilment centre" + (requiredZone == null ? "" : " with " + requiredZone
                        + " storage")))
                .orElseThrow(() -> new InventoryRuleViolation("inventory.fc-unavailable", "No fulfilment centre can "
                        + "take these SKUs" + (requiredZone == null ? "" : " with " + requiredZone + " storage")
                        + " without mixing them with another seller's stock"));
    }
}
