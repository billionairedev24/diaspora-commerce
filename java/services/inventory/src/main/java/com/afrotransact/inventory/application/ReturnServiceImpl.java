package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.InventoryStateConflict;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;
import com.afrotransact.inventory.domain.ReturnCondition;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.IdempotencyStore;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.ReservationRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReturnServiceImpl implements ReturnService {

    private static final String SCOPE = "return";
    private static final List<MovementType> RETURN_TYPES = List.of(MovementType.RETURN_RESTOCK,
            MovementType.RETURN_DAMAGED);

    private final ReservationRepository reservations;
    private final PositionRepository positions;
    private final LotRepository lots;
    private final LedgerRepository ledger;
    private final NodeRepository nodes;
    private final StockWriter writer;
    private final IdempotencyStore idempotency;
    private final InventoryAccess access;
    private final Clock clock;

    ReturnServiceImpl(ReservationRepository reservations, PositionRepository positions, LotRepository lots,
                      LedgerRepository ledger, NodeRepository nodes, StockWriter writer, IdempotencyStore idempotency,
                      InventoryAccess access, Clock clock) {
        this.reservations = reservations;
        this.positions = positions;
        this.lots = lots;
        this.ledger = ledger;
        this.nodes = nodes;
        this.writer = writer;
        this.idempotency = idempotency;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReturnResult accept(ReturnCommand raw, String idempotencyKey) {
        Actor actor = access.returner();
        ReturnCommand cmd = normalise(raw);
        Optional<ReturnResult> prior = idempotency.claim(SCOPE, idempotencyKey, Fingerprint.of(cmd),
                ReturnResult.class);
        if (prior.isPresent()) {
            return prior.get().asReplay();
        }
        Reservation order = reservations.lockByOrderId(cmd.orderId()).orElseThrow(() -> new NotFoundException(
                "inventory.reservation-not-found", "No reservation for order " + cmd.orderId()));
        if (order.committedLines().isEmpty()) {
            throw new InventoryStateConflict("inventory.order-not-shipped", "Order " + cmd.orderId() + " is "
                    + order.status() + "; only shipped orders can be returned");
        }
        List<ReservationLine> lines = order.committedLines().stream().filter(l -> l.sku().equals(cmd.sku()))
                .toList();
        int shipped = lines.stream().mapToInt(ReservationLine::qty).sum();
        int returned = ledger.sumQty(cmd.orderId(), cmd.sku(), RETURN_TYPES);
        if (shipped == 0 || returned + cmd.qty() > shipped) {
            throw new InventoryRuleViolation("inventory.return-exceeds-shipped", "Order " + cmd.orderId() + " shipped "
                    + shipped + " of " + cmd.sku() + " and " + returned + " were already returned",
                    Map.of("qty", "At most " + Math.max(0, shipped - returned)));
        }
        LotAllocation source = lines.stream().flatMap(l -> l.allocations().stream())
                .filter(a -> cmd.lotId() == null || a.lotId().equals(cmd.lotId()))
                .max(Comparator.comparing(LotAllocation::qty))
                .orElseThrow(() -> new InventoryRuleViolation("inventory.invalid-request",
                        "Lot " + cmd.lotId() + " was not shipped on order " + cmd.orderId(), Map.of("lotId", "Unknown")));

        StockChanges changes = writer.begin(actor.subject());
        Lot shippedLot = lots.lockById(source.lotId()).orElseThrow();
        String node = cmd.receivingNodeId() == null ? shippedLot.nodeId() : cmd.receivingNodeId();
        if (!node.equals(shippedLot.nodeId())) {
            Node receiving = nodes.findById(node).orElseThrow(() -> new NotFoundException("inventory.node-not-found",
                    "Unknown receiving node " + node));
            if (!receiving.active()) {
                throw new InventoryRuleViolation("inventory.node-inactive", "Node " + node + " is not active",
                        Map.of("receivingNodeId", "Inactive"));
            }
            positions.ensureExists(cmd.sku(), node, shippedLot.ownerOrg(), changes.now());
        }
        positions.lockBySkus(List.of(cmd.sku())).forEach(changes::track);
        changes.trackLot(shippedLot);
        StockPosition position = changes.requirePosition(shippedLot.sku(), node);
        if (!position.ownerOrg().equals(shippedLot.ownerOrg())) {
            throw new InventoryStateConflict("inventory.owner-mismatch", "SKU " + cmd.sku() + " at " + node
                    + " belongs to another organisation; FBA stock is never mixed");
        }
        String why = "Return " + cmd.condition().name().toLowerCase(Locale.ROOT) + ": " + cmd.reason()
                + (node.equals(shippedLot.nodeId()) ? "" : " (received at " + node + ")");
        Lot target;
        boolean created = false;
        if (cmd.condition() == ReturnCondition.RESTOCK) {
            if (shippedLot.status() != LotStatus.SELLABLE || shippedLot.expiredOn(today(shippedLot.nodeId()))) {
                throw new InventoryRuleViolation("inventory.return-not-sellable", "Lot " + shippedLot.lotNo() + " is "
                        + shippedLot.status() + "; record the return as DAMAGED", Map.of("condition", "Use DAMAGED"));
            }
            Optional<Lot> here = node.equals(shippedLot.nodeId()) ? Optional.of(shippedLot)
                    : lots.lockByKey(shippedLot.sku(), shippedLot.lotNo(), node);
            if (here.isPresent() && here.get().status() != LotStatus.SELLABLE) {
                throw new InventoryRuleViolation("inventory.return-not-sellable", "Lot " + shippedLot.lotNo() + " at "
                        + node + " is " + here.get().status() + "; record the return as DAMAGED",
                        Map.of("condition", "Use DAMAGED"));
            }
            if (here.isPresent()) {
                changes.trackLot(here.get());
                target = changes.lot(here.get().id()).add(cmd.qty());
            } else {
                target = new Lot(UUID.randomUUID(), shippedLot.sku(), shippedLot.lotNo(), node,
                        shippedLot.ownerOrg(), null, cmd.qty(), 0, shippedLot.mfgDate(), shippedLot.expDate(),
                        changes.now(), shippedLot.cost(), LotStatus.SELLABLE,
                        "Returned to " + node, shippedLot.markdownFlag(), cmd.orderId(), 0);
                changes.newLot(target);
                created = true;
            }
            changes.apply(MovementType.RETURN_RESTOCK, position.adjustSellable(cmd.qty()), target, cmd.qty(),
                    cmd.orderId(), why);
        } else {
            Lot lot = shippedLot;
            String dmgNo = lot.isDamagedLot() ? lot.lotNo() : lot.lotNo() + Lot.DAMAGED_SUFFIX;
            Optional<Lot> existing = lots.lockByKey(lot.sku(), dmgNo, node);
            if (existing.isPresent() && existing.get().status() != LotStatus.WRITTEN_OFF) {
                changes.trackLot(existing.get());
                target = existing.get().add(cmd.qty());
            } else if (existing.isPresent()) {
                throw new InventoryStateConflict("inventory.invalid-lot-state", "Damaged lot " + dmgNo
                        + " was written off; write off the returned units directly");
            } else {
                target = new Lot(UUID.randomUUID(), lot.sku(), dmgNo, node, lot.ownerOrg(), null, cmd.qty(), 0,
                        lot.mfgDate(), lot.expDate(), changes.now(), lot.cost(),
                        LotStatus.DAMAGED, "Damaged return", MarkdownFlag.NONE, cmd.orderId(), 0);
                changes.newLot(target);
                created = true;
            }
            changes.apply(MovementType.RETURN_DAMAGED, position.adjustHeld(cmd.qty()), target, cmd.qty(),
                    cmd.orderId(), why);
        }
        writer.flush(changes);
        Lot stored = changes.lot(target.id());
        if (created) {
            writer.publishLot(InventoryEvents.LOT_CREATED, stored, stored.qtyOnHand());
        }
        ReturnResult result = new ReturnResult(cmd.orderId(), cmd.sku(), cmd.qty(), cmd.condition(), stored, false);
        idempotency.complete(SCOPE, idempotencyKey, result);
        return result;
    }

    private LocalDate today(String nodeId) {
        return nodes.findById(nodeId).map(n -> n.today(clock)).orElseThrow();
    }

    private static ReturnCommand normalise(ReturnCommand c) {
        if (c.orderId() == null || c.orderId().isBlank() || c.sku() == null || c.sku().isBlank() || c.qty() <= 0
                || c.condition() == null || c.reason() == null || c.reason().isBlank()) {
            throw new InventoryRuleViolation("inventory.invalid-request",
                    "orderId, sku, a positive qty, condition and reason are required");
        }
        return new ReturnCommand(c.orderId().trim(), c.sku().trim().toUpperCase(Locale.ROOT), c.lotId(), c.qty(),
                c.condition(), c.reason().trim(), c.receivingNodeId() == null || c.receivingNodeId().isBlank() ? null
                : c.receivingNodeId().trim().toUpperCase(Locale.ROOT));
    }
}
