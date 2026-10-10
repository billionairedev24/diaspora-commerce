package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Persists a {@link StockChanges} unit of work inside the caller's transaction: lots, positions (version + 1),
 * ledger rows and an {@code inventory.availability.changed} outbox event for every position whose available moved.
 */
@Component
class StockWriter {

    private final PositionRepository positions;
    private final LotRepository lots;
    private final LedgerRepository ledger;
    private final DomainEventPublisher events;
    private final Clock clock;

    StockWriter(PositionRepository positions, LotRepository lots, LedgerRepository ledger, DomainEventPublisher events,
                Clock clock) {
        this.positions = positions;
        this.lots = lots;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    StockChanges begin(String actor) {
        String correlation = MDC.get("correlationId");
        return new StockChanges(actor, correlation == null ? "job-" + UUID.randomUUID() : correlation,
                Instant.now(clock));
    }

    /** Writes everything and returns the stored positions (with their new versions). */
    List<StockPosition> flush(StockChanges changes) {
        for (Lot created : changes.createdLots()) {
            lots.insert(created);
        }
        for (Lot changed : changes.changedLots()) {
            lots.update(changed);
        }
        List<StockPosition> stored = new ArrayList<>();
        for (StockPosition p : changes.changedPositions()) {
            StockPosition saved = positions.update(p, changes.now());
            stored.add(saved);
            if (changes.availabilityChanged(p)) {
                events.publish(InventoryEvents.AVAILABILITY_CHANGED, InventoryEvents.AGGREGATE_STOCK, saved.sku(),
                        new AvailabilityChangedPayload(saved.sku(), saved.nodeId(), saved.ownerOrg(), saved.available(),
                                saved.onHand(), saved.reserved(), saved.inbound(), saved.damaged(), saved.version()));
            }
        }
        ledger.append(changes.movements());
        return stored;
    }

    void publishLot(String type, Lot lot, int qty) {
        events.publish(type, InventoryEvents.AGGREGATE_STOCK, lot.sku(), LotEventPayload.of(lot, qty));
    }
}
