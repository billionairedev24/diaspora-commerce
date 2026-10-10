package com.afrotransact.inventory.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.Movement;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.StockPosition;

/**
 * Unit of work for one stock-changing transaction: tracks the locked positions and lots, applies changes in memory
 * and records one ledger row per change with the position's before/after levels. {@link StockWriter} flushes it.
 */
final class StockChanges {

    private final String actor;
    private final String correlationId;
    private final Instant now;
    private final Map<String, StockPosition> initialPositions = new LinkedHashMap<>();
    private final Map<String, StockPosition> positions = new LinkedHashMap<>();
    private final Map<UUID, Lot> initialLots = new LinkedHashMap<>();
    private final Map<UUID, Lot> lots = new LinkedHashMap<>();
    private final Set<UUID> newLots = new LinkedHashSet<>();
    private final List<Movement> movements = new ArrayList<>();

    StockChanges(String actor, String correlationId, Instant now) {
        this.actor = actor;
        this.correlationId = correlationId;
        this.now = now;
    }

    Instant now() {
        return now;
    }

    /** Starts tracking a position that this transaction has locked. */
    void track(StockPosition p) {
        String key = key(p.sku(), p.nodeId());
        initialPositions.putIfAbsent(key, p);
        positions.putIfAbsent(key, p);
    }

    void trackLot(Lot lot) {
        initialLots.putIfAbsent(lot.id(), lot);
        lots.putIfAbsent(lot.id(), lot);
    }

    void newLot(Lot lot) {
        newLots.add(lot.id());
        lots.put(lot.id(), lot);
    }

    Optional<StockPosition> position(String sku, String nodeId) {
        return Optional.ofNullable(positions.get(key(sku, nodeId)));
    }

    StockPosition requirePosition(String sku, String nodeId) {
        return position(sku, nodeId).orElseThrow(() -> new IllegalStateException(
                "Position " + sku + "@" + nodeId + " was not locked by this transaction"));
    }

    List<StockPosition> positionsOf(String sku) {
        return positions.values().stream().filter(p -> p.sku().equals(sku)).toList();
    }

    Lot lot(UUID id) {
        Lot lot = lots.get(id);
        if (lot == null) {
            throw new IllegalStateException("Lot " + id + " was not locked by this transaction");
        }
        return lot;
    }

    /**
     * Applies a change: {@code after} becomes the position's current state and a ledger row is recorded.
     *
     * @param lotAfter the lot's new state, or null for position-only movements
     */
    void apply(MovementType type, StockPosition after, Lot lotAfter, int qty, String reference, String reason) {
        String key = key(after.sku(), after.nodeId());
        StockPosition before = positions.get(key);
        if (before == null) {
            throw new IllegalStateException("Position " + key + " was not locked by this transaction");
        }
        positions.put(key, after);
        if (lotAfter != null) {
            lots.put(lotAfter.id(), lotAfter);
        }
        movements.add(new Movement(0, now, type, after.sku(), after.nodeId(), after.ownerOrg(),
                lotAfter == null ? null : lotAfter.id(), lotAfter == null ? null : lotAfter.lotNo(), Math.abs(qty),
                before.levels(), after.levels(), reference, actor, reason, correlationId));
    }

    List<Movement> movements() {
        return List.copyOf(movements);
    }

    Collection<Lot> createdLots() {
        return newLots.stream().map(lots::get).toList();
    }

    Collection<Lot> changedLots() {
        return lots.values().stream()
                .filter(l -> !newLots.contains(l.id()) && !l.equals(initialLots.get(l.id())))
                .toList();
    }

    List<StockPosition> changedPositions() {
        return positions.entrySet().stream()
                .filter(e -> !e.getValue().levels().equals(initialPositions.get(e.getKey()).levels()))
                .map(Map.Entry::getValue)
                .toList();
    }

    boolean availabilityChanged(StockPosition p) {
        StockPosition initial = initialPositions.get(key(p.sku(), p.nodeId()));
        return initial == null || initial.available() != p.available();
    }

    private static String key(String sku, String nodeId) {
        return sku + "|" + nodeId;
    }
}
