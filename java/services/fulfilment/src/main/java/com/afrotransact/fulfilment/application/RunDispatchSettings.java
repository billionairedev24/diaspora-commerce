package com.afrotransact.fulfilment.application;

import java.util.Set;

/**
 * How runs are planned ({@code at.fulfilment.runs.*}, S264).
 *
 * @param depotNodeId    the fulfilment centre run routes load out of and failed packages return to (blank: the
 *                       default node)
 * @param routeCapacity  the most bags (packages) one route carries; a bigger run is split into balanced routes
 * @param depotSellers   seller organisations whose goods are already at the depot (AfroTransact Retail): their bags
 *                       are loaded at the FC and scanned at load-out instead of collected at a pickup stop
 */
public record RunDispatchSettings(String depotNodeId, int routeCapacity, Set<String> depotSellers) {

    public RunDispatchSettings {
        depotNodeId = depotNodeId == null || depotNodeId.isBlank() ? null : depotNodeId.trim();
        routeCapacity = Math.max(1, routeCapacity);
        depotSellers = depotSellers == null ? Set.of() : Set.copyOf(depotSellers);
    }

    public boolean atDepot(String sellerOrgId) {
        return sellerOrgId != null && depotSellers.contains(sellerOrgId);
    }
}
