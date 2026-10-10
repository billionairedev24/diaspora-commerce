package com.afrotransact.inventory.domain;

/** The destination chosen for an inbound plan and why. */
public record FcAssignment(String nodeId, String reason) {
}
