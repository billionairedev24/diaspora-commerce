package com.afrotransact.identity.application;

import java.util.Set;

/**
 * Clients the platform itself signs in with (gateway-bff): they cannot be disabled from the console, so an admin cannot
 * lock every staff member out, including themselves.
 */
public record ProtectedClients(Set<String> clientIds) {

    public ProtectedClients {
        clientIds = Set.copyOf(clientIds);
    }

    public boolean contains(String clientId) {
        return clientIds.contains(clientId);
    }
}
