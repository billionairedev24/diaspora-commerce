package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.InventoryStateConflict;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.IdempotencyStore;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LotServiceImpl implements LotService {

    private final LotRepository lots;
    private final PositionRepository positions;
    private final NodeRepository nodes;
    private final StockWriter writer;
    private final IdempotencyStore idempotency;
    private final InventoryAccess access;
    private final Clock clock;

    LotServiceImpl(LotRepository lots, PositionRepository positions, NodeRepository nodes, StockWriter writer,
                   IdempotencyStore idempotency, InventoryAccess access, Clock clock) {
        this.lots = lots;
        this.positions = positions;
        this.nodes = nodes;
        this.writer = writer;
        this.idempotency = idempotency;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Lot get(UUID id) {
        Optional<String> scope = access.readScope();
        Lot lot = lots.findById(id).orElseThrow(() -> notFound(id));
        access.ensureVisible(scope, lot.ownerOrg(), "Lot " + id);
        return lot;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Lot> search(LotRepository.LotQuery q) {
        String owner = access.ownerFilter(q.ownerOrg());
        return lots.search(new LotRepository.LotQuery(upper(q.sku()), upper(q.nodeId()), owner, q.status(),
                upper(q.lotNo()), Math.max(q.page(), 0), Math.clamp(q.size(), 1, 200)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Lot> markdown(MarkdownFlag minimum) {
        String owner = access.ownerFilter(null);
        return lots.findMarkdown(owner, minimum == null || minimum == MarkdownFlag.NONE ? MarkdownFlag.WITHIN_60
                : minimum);
    }

    @Override
    @Transactional
    public Lot quarantine(UUID id, String reason) {
        String why = reason(reason);
        return change(id, MovementType.QUARANTINE, (lot, position) -> {
            Lot held = lot.quarantine(why);
            return new Change(held, lot.qtyOnHand() == 0 ? position : position.hold(lot.qtyOnHand()),
                    lot.qtyOnHand());
        }, why, InventoryEvents.LOT_QUARANTINED);
    }

    @Override
    @Transactional
    public Lot release(UUID id, String reason) {
        String why = reason(reason);
        return change(id, MovementType.RELEASE_QUARANTINE, (lot, position) -> {
            Lot released = lot.releaseQuarantine(why, today(lot.nodeId()));
            return new Change(released, lot.qtyOnHand() == 0 ? position : position.unhold(lot.qtyOnHand()),
                    lot.qtyOnHand());
        }, why, InventoryEvents.LOT_RELEASED);
    }

    @Override
    @Transactional
    public Lot writeOff(UUID id, Integer qty, String reason, String idempotencyKey) {
        String why = reason(reason);
        record Cmd(UUID id, Integer qty, String reason) {
        }
        Optional<Lot> prior = idempotency.claim("write-off", idempotencyKey, Fingerprint.of(new Cmd(id, qty, why)),
                Lot.class);
        if (prior.isPresent()) {
            return prior.get();
        }
        Lot result = change(id, MovementType.WRITE_OFF, (lot, position) -> {
            int units = qty == null ? lot.qtyOnHand() - lot.qtyReserved() : qty;
            Lot written = lot.writeOff(units, why);
            StockPosition next = lot.status() == LotStatus.SELLABLE ? position.adjustSellable(-units)
                    : position.adjustHeld(-units);
            return new Change(written, next, units);
        }, why, InventoryEvents.LOT_WRITTEN_OFF);
        idempotency.complete("write-off", idempotencyKey, result);
        return result;
    }

    @Override
    @Transactional
    public Lot adjust(UUID id, int countedQty, String reason, String idempotencyKey) {
        String why = reason(reason);
        if (countedQty < 0) {
            throw new InventoryRuleViolation("inventory.invalid-quantity", "Counted quantity cannot be negative",
                    Map.of("countedQty", "Must be ≥ 0"));
        }
        record Cmd(UUID id, int counted, String reason) {
        }
        Optional<Lot> prior = idempotency.claim("adjust", idempotencyKey, Fingerprint.of(new Cmd(id, countedQty, why)),
                Lot.class);
        if (prior.isPresent()) {
            return prior.get();
        }
        Lot result = change(id, MovementType.ADJUST, (lot, position) -> {
            if (lot.status() == LotStatus.WRITTEN_OFF) {
                throw new InventoryStateConflict("inventory.invalid-lot-state", "Lot " + lot.lotNo()
                        + " is written off");
            }
            int delta = countedQty - lot.qtyOnHand();
            StockPosition next = lot.status() == LotStatus.SELLABLE ? position.adjustSellable(delta)
                    : position.adjustHeld(delta);
            return new Change(lot.countedAs(countedQty, "Cycle count: " + why), next, delta);
        }, why, null);
        idempotency.complete("adjust", idempotencyKey, result);
        return result;
    }

    @Override
    @Transactional
    public TransferResult transfer(UUID id, TransferCommand cmd, String idempotencyKey) {
        Actor actor = access.require(InventoryPermissions.ADJUST);
        String why = reason(cmd.reason());
        Optional<TransferResult> prior = idempotency.claim("transfer", idempotencyKey,
                Fingerprint.of(List.of(id, cmd)), TransferResult.class);
        if (prior.isPresent()) {
            return prior.get();
        }
        Lot source = lots.findById(id).orElseThrow(() -> notFound(id));
        Node target = nodes.findById(upper(cmd.toNodeId())).filter(Node::active)
                .orElseThrow(() -> new NotFoundException("inventory.node-not-found", "Unknown node " + cmd.toNodeId()));
        if (target.id().equals(source.nodeId())) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Pick a different destination node",
                    Map.of("toNodeId", "Same as source"));
        }
        if (cmd.qty() <= 0) {
            throw new InventoryRuleViolation("inventory.invalid-quantity", "Quantity must be positive",
                    Map.of("qty", "Must be at least 1"));
        }
        StockChanges changes = writer.begin(actor.subject());
        positions.ensureExists(source.sku(), target.id(), source.ownerOrg(), changes.now());
        positions.lockBySkus(List.of(source.sku())).forEach(changes::track);
        Lot from = lots.lockById(id).orElseThrow(() -> notFound(id));
        changes.trackLot(from);
        if (from.status() != LotStatus.SELLABLE) {
            throw new InventoryStateConflict("inventory.invalid-lot-state", "Only sellable lots can be transferred");
        }
        if (cmd.qty() > from.qtyOnHand() - from.qtyReserved()) {
            throw new InventoryRuleViolation("inventory.below-reserved", "Lot " + from.lotNo() + " has only "
                    + (from.qtyOnHand() - from.qtyReserved()) + " unreserved units", Map.of("qty", "Too many"));
        }
        StockPosition targetPosition = changes.requirePosition(from.sku(), target.id());
        if (!targetPosition.ownerOrg().equals(from.ownerOrg())) {
            throw new ConflictException("inventory.owner-mismatch", "Destination stock belongs to another organisation");
        }
        String reference = "TO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Lot fromAfter = from.remove(cmd.qty());
        changes.apply(MovementType.TRANSFER, changes.requirePosition(from.sku(), from.nodeId())
                .adjustSellable(-cmd.qty()), fromAfter, cmd.qty(), reference, "Out to " + target.id() + ": " + why);

        Optional<Lot> existing = lots.lockByKey(from.sku(), from.lotNo(), target.id());
        Lot to;
        boolean created = existing.isEmpty();
        if (existing.isPresent()) {
            changes.trackLot(existing.get());
            if (existing.get().status() != LotStatus.SELLABLE) {
                throw new InventoryStateConflict("inventory.invalid-lot-state", "Lot " + from.lotNo() + " at "
                        + target.id() + " is " + existing.get().status());
            }
            to = existing.get().add(cmd.qty());
        } else {
            to = new Lot(UUID.randomUUID(), from.sku(), from.lotNo(), target.id(), from.ownerOrg(), upper(cmd.toBin()),
                    cmd.qty(), 0, from.mfgDate(), from.expDate(), from.receivedAt(), from.unitLandedCostMinor(),
                    from.currency(), LotStatus.SELLABLE, "Transferred from " + from.nodeId(), from.markdownFlag(),
                    reference, 0);
            changes.newLot(to);
        }
        changes.apply(MovementType.TRANSFER, targetPosition.adjustSellable(cmd.qty()), to, cmd.qty(), reference,
                "In from " + from.nodeId() + ": " + why);
        writer.flush(changes);
        if (created) {
            writer.publishLot(InventoryEvents.LOT_CREATED, to, to.qtyOnHand());
        }
        TransferResult result = new TransferResult(reference, changes.lot(fromAfter.id()), changes.lot(to.id()));
        idempotency.complete("transfer", idempotencyKey, result);
        return result;
    }

    /** Locks the lot's position (key order), then the lot; applies the change; writes ledger and events. */
    private Lot change(UUID id, MovementType type, BiFunction<Lot, StockPosition, Change> change, String reason,
                       String eventType) {
        Actor actor = access.require(InventoryPermissions.ADJUST);
        Lot unlocked = lots.findById(id).orElseThrow(() -> notFound(id));
        StockChanges changes = writer.begin(actor.subject());
        positions.lockBySkus(List.of(unlocked.sku())).forEach(changes::track);
        Lot lot = lots.lockById(id).orElseThrow(() -> notFound(id));
        changes.trackLot(lot);
        Change result = change.apply(lot, changes.requirePosition(lot.sku(), lot.nodeId()));
        changes.apply(type, result.position(), result.lot(), result.qty(), "LOT-" + lot.lotNo(), reason);
        writer.flush(changes);
        Lot stored = lots.findById(id).orElseThrow(() -> notFound(id));
        if (eventType != null) {
            writer.publishLot(eventType, stored, Math.abs(result.qty()));
        }
        return stored;
    }

    private LocalDate today(String nodeId) {
        return nodes.findById(nodeId).map(n -> n.today(clock)).orElseGet(() -> LocalDate.now(clock));
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InventoryRuleViolation("inventory.reason-required", "A reason is required for stock changes",
                    Map.of("reason", "Required"));
        }
        return reason.trim();
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("inventory.lot-not-found", "Lot " + id + " not found");
    }

    private record Change(Lot lot, StockPosition position, int qty) {
    }
}
