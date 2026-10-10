package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.inventory.domain.InboundLine;
import com.afrotransact.inventory.domain.InboundShipment;
import com.afrotransact.inventory.domain.InboundStatus;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.ShelfLifeDecision;
import com.afrotransact.inventory.domain.ShelfLifePolicy;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.IdempotencyStore;
import com.afrotransact.inventory.port.InboundShipmentRepository;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReceivingServiceImpl implements ReceivingService {

    static final Pattern LOT_NO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,58}");
    private static final String SCOPE = "receipt";

    private final NodeRepository nodes;
    private final PositionRepository positions;
    private final LotRepository lots;
    private final ShelfLifePolicy shelfLife;
    private final StockWriter writer;
    private final IdempotencyStore idempotency;
    private final InventoryAccess access;
    private final InventorySettings settings;
    private final InboundShipmentRepository inbounds;
    private final DomainEventPublisher events;
    private final Clock clock;

    ReceivingServiceImpl(NodeRepository nodes, PositionRepository positions, LotRepository lots,
                         ShelfLifePolicy shelfLife, StockWriter writer, IdempotencyStore idempotency,
                         InventoryAccess access, InventorySettings settings, InboundShipmentRepository inbounds,
                         DomainEventPublisher events, Clock clock) {
        this.nodes = nodes;
        this.positions = positions;
        this.lots = lots;
        this.shelfLife = shelfLife;
        this.writer = writer;
        this.idempotency = idempotency;
        this.access = access;
        this.settings = settings;
        this.inbounds = inbounds;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReceiptResult receive(ReceiveCommand raw, String idempotencyKey) {
        Actor actor = access.require(InventoryPermissions.RECEIVE);
        ReceiveCommand cmd = normalise(raw);
        Optional<ReceiptResult> prior = idempotency.claim(SCOPE, idempotencyKey, Fingerprint.of(cmd),
                ReceiptResult.class);
        if (prior.isPresent()) {
            return prior.get().asReplay();
        }
        Node node = activeNode(cmd.nodeId());
        LocalDate today = node.today(clock);
        List<ShelfLifeDecision> decisions = assess(cmd, today);
        // Lock order: inbound shipment, then positions (sku, node), then lots.
        Optional<InboundShipment> inbound = receivableInbound(cmd, node);

        StockChanges changes = writer.begin(actor.subject());
        TreeSet<String> skus = new TreeSet<>(cmd.lines().stream().map(ReceiveCommand.Line::sku).toList());
        skus.forEach(sku -> positions.ensureExists(sku, node.id(), cmd.ownerOrg(), changes.now()));
        positions.lockBySkus(skus).forEach(changes::track);

        List<Lot> created = new ArrayList<>();
        List<Lot> quarantined = new ArrayList<>();
        for (int i = 0; i < cmd.lines().size(); i++) {
            ReceiveCommand.Line line = cmd.lines().get(i);
            StockPosition position = changes.requirePosition(line.sku(), node.id());
            if (!position.ownerOrg().equals(cmd.ownerOrg())) {
                throw new ConflictException("inventory.owner-mismatch", "SKU " + line.sku() + " at " + node.id()
                        + " belongs to another organisation; FBA stock is never mixed");
            }
            int total = line.qtyGood() + line.qtyDamaged();
            int inboundConsumed;
            if (inbound.isPresent()) {
                // Exactly this shipment's expected units become stock (S226, S257).
                InboundShipment s = inbound.get();
                int lineOpen = s.line(line.sku()).map(InboundLine::inboundOpen).orElse(0);
                int fromLine = Math.min(lineOpen, total);
                inbound = Optional.of(s.receive(line.sku(), null, line.qtyGood(), line.qtyDamaged(), fromLine,
                        changes.now()));
                inboundConsumed = Math.min(position.inbound(), fromLine);
            } else {
                inboundConsumed = Math.min(position.inbound(), total);
            }
            if (inboundConsumed > 0) {
                position = position.adjustInbound(-inboundConsumed);
                changes.apply(MovementType.INBOUND, position, null, inboundConsumed, cmd.receiptRef(),
                        inbound.map(s -> "Arrived on inbound shipment " + s.number())
                                .orElse("Arrived against inbound notice"));
            }
            ShelfLifeDecision decision = decisions.get(i);
            if (line.qtyGood() > 0) {
                Lot lot = receiveLot(changes, cmd, node, line, line.lotNo(), line.qtyGood());
                created.add(lot);
                if (decision.outcome() == ShelfLifeDecision.Outcome.QUARANTINE) {
                    String reason = line.holdReason() == null ? decision.reason()
                            : decision.reason() + "; " + line.holdReason();
                    quarantined.add(quarantine(changes, cmd, lot, reason));
                } else if (line.holdReason() != null) {
                    quarantined.add(quarantine(changes, cmd, lot, line.holdReason()));
                }
            }
            if (line.qtyDamaged() > 0) {
                Lot damaged = receiveLot(changes, cmd, node, line, line.lotNo() + Lot.DAMAGED_SUFFIX,
                        line.qtyDamaged());
                created.add(damaged);
                quarantined.add(quarantine(changes, cmd, damaged, "Damaged on receipt"));
            }
        }
        writer.flush(changes);
        List<Lot> finalLots = created.stream().map(l -> changes.lot(l.id())).toList();
        finalLots.forEach(l -> writer.publishLot(InventoryEvents.LOT_CREATED, l, l.qtyOnHand()));
        quarantined.forEach(l -> writer.publishLot(InventoryEvents.LOT_QUARANTINED, changes.lot(l.id()),
                l.qtyOnHand()));
        inbound.ifPresent(s -> recordInboundReceipt(s, cmd, changes.now()));
        ReceiptResult result = new ReceiptResult(cmd.receiptRef(), node.id(), cmd.ownerOrg(), finalLots, false);
        idempotency.complete(SCOPE, idempotencyKey, result);
        return result;
    }

    /**
     * The inbound shipment named by the receipt, locked, when it can take the units: same owner and node, shipped or
     * receiving. Anything else (unknown, closed, another FC) leaves the receipt unlinked, so a 1P receipt is never
     * blocked by its notice.
     */
    private Optional<InboundShipment> receivableInbound(ReceiveCommand cmd, Node node) {
        if (cmd.inboundRef() == null) {
            return Optional.empty();
        }
        Optional<InboundShipment> found = inbounds.lockByReference(cmd.inboundRef());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        InboundShipment s = found.get();
        if (!s.ownerOrg().equals(cmd.ownerOrg()) || !s.nodeId().equals(node.id())) {
            throw new InventoryRuleViolation("inventory.inbound-mismatch", "Inbound shipment " + s.number()
                    + " brings " + s.ownerOrg() + " stock to " + s.nodeId() + ", not " + cmd.ownerOrg() + " stock to "
                    + node.id(), Map.of("inboundRef", "Belongs to another owner or fulfilment centre"));
        }
        if (s.status() != InboundStatus.SHIPPED && s.status() != InboundStatus.RECEIVING) {
            return Optional.empty();
        }
        return found;
    }

    private void recordInboundReceipt(InboundShipment received, ReceiveCommand cmd, Instant at) {
        InboundShipment saved = inbounds.update(received.countReceipt(at));
        List<InboundEventPayloads.ReceivedLine> lines = new ArrayList<>();
        for (ReceiveCommand.Line l : cmd.lines()) {
            InboundLine planned = saved.line(l.sku()).orElse(null);
            lines.add(new InboundEventPayloads.ReceivedLine(l.sku(), planned == null ? null : planned.atin(),
                    planned == null ? null : planned.unitLabel(), l.qtyGood(), l.qtyDamaged()));
        }
        events.publish(InventoryEvents.INBOUND_RECEIVED, InventoryEvents.AGGREGATE_INBOUND, saved.id().toString(),
                new InboundEventPayloads.InboundReceived(saved.id(), saved.number(), saved.source().name(),
                        saved.ownerOrg(), saved.nodeId(), cmd.receiptRef(), lines, at));
    }

    @Override
    @Transactional
    public StockPosition expectInbound(InboundCommand raw) {
        Actor actor = access.require(InventoryPermissions.RECEIVE);
        if (raw.qty() == 0 || raw.sku() == null || raw.sku().isBlank()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "sku and a non-zero qty are required");
        }
        Node node = activeNode(raw.nodeId());
        String sku = raw.sku().trim().toUpperCase(Locale.ROOT);
        String owner = owner(raw.ownerOrg());
        StockChanges changes = writer.begin(actor.subject());
        positions.ensureExists(sku, node.id(), owner, changes.now());
        positions.lockBySkus(List.of(sku)).forEach(changes::track);
        StockPosition position = changes.requirePosition(sku, node.id());
        if (!position.ownerOrg().equals(owner)) {
            throw new ConflictException("inventory.owner-mismatch", "SKU " + sku + " belongs to another organisation");
        }
        changes.apply(MovementType.INBOUND, position.adjustInbound(raw.qty()), null, raw.qty(), raw.reference(),
                raw.qty() > 0 ? "Inbound notice" : "Inbound cancelled");
        return writer.flush(changes).stream().findFirst().orElse(position);
    }

    private Lot receiveLot(StockChanges changes, ReceiveCommand cmd, Node node, ReceiveCommand.Line line, String lotNo,
                           int qty) {
        if (lots.lockByKey(line.sku(), lotNo, node.id()).isPresent()) {
            throw new ConflictException("inventory.lot-exists", "Lot " + lotNo + " of " + line.sku()
                    + " was already received at " + node.id() + "; lot numbers must be new");
        }
        Lot lot = new Lot(UUID.randomUUID(), line.sku(), lotNo, node.id(), cmd.ownerOrg(), line.bin(), qty, 0,
                line.mfgDate(), line.expDate(), changes.now(), line.unitLandedCostMinor(), line.currency(),
                LotStatus.SELLABLE, "Received on " + cmd.receiptRef(), MarkdownFlag.of(line.expDate(), node.today(clock)),
                cmd.receiptRef(), 0);
        changes.newLot(lot);
        StockPosition position = changes.requirePosition(line.sku(), node.id()).adjustSellable(qty);
        changes.apply(MovementType.RECEIVE, position, lot, qty, cmd.receiptRef(), "Goods receipt");
        changes.apply(MovementType.PUT_AWAY, position, lot, qty, cmd.receiptRef(),
                "Put away to bin " + (line.bin() == null ? "unassigned" : line.bin()));
        return lot;
    }

    private static Lot quarantine(StockChanges changes, ReceiveCommand cmd, Lot lot, String reason) {
        Lot held = lot.quarantine(reason);
        StockPosition position = changes.requirePosition(lot.sku(), lot.nodeId()).hold(lot.qtyOnHand());
        changes.apply(MovementType.QUARANTINE, position, held, lot.qtyOnHand(), cmd.receiptRef(), reason);
        return held;
    }

    private List<ShelfLifeDecision> assess(ReceiveCommand cmd, LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<ShelfLifeDecision> decisions = new ArrayList<>();
        for (int i = 0; i < cmd.lines().size(); i++) {
            ReceiveCommand.Line line = cmd.lines().get(i);
            ShelfLifeDecision d = shelfLife.assess(line.mfgDate(), line.expDate(), line.shelfLifeDays(), today);
            if (d.outcome() == ShelfLifeDecision.Outcome.REJECT) {
                errors.put("lines[" + i + "].expDate", d.reason());
            }
            decisions.add(d);
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.lot-rejected",
                    "Receipt rejected: expired lots or impossible dates", errors);
        }
        return decisions;
    }

    private ReceiveCommand normalise(ReceiveCommand cmd) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (cmd.receiptRef() == null || cmd.receiptRef().isBlank()) {
            errors.put("receiptRef", "Required");
        }
        if (cmd.nodeId() == null || cmd.nodeId().isBlank()) {
            errors.put("nodeId", "Required");
        }
        if (cmd.lines().isEmpty() || cmd.lines().size() > 500) {
            errors.put("lines", "1 to 500 lines");
        }
        Set<String> seen = new HashSet<>();
        List<ReceiveCommand.Line> lines = new ArrayList<>();
        for (int i = 0; i < cmd.lines().size(); i++) {
            ReceiveCommand.Line l = cmd.lines().get(i);
            String p = "lines[" + i + "].";
            if (l.sku() == null || l.sku().isBlank()) {
                errors.put(p + "sku", "Required");
            }
            if (l.lotNo() == null || !LOT_NO.matcher(l.lotNo()).matches() || l.lotNo().endsWith(Lot.DAMAGED_SUFFIX)) {
                errors.put(p + "lotNo", "Letters, digits, . _ / - (max 59), not ending in " + Lot.DAMAGED_SUFFIX);
            }
            if (l.qtyGood() < 0 || l.qtyDamaged() < 0 || l.qtyGood() + l.qtyDamaged() == 0) {
                errors.put(p + "qtyGood", "Good and damaged quantities must be ≥ 0 and not both 0");
            }
            if (l.unitLandedCostMinor() < 0) {
                errors.put(p + "unitLandedCostMinor", "Must be ≥ 0");
            }
            if (l.currency() == null || !l.currency().matches("[A-Za-z]{3}")) {
                errors.put(p + "currency", "ISO 4217 code");
            }
            if (l.shelfLifeDays() != null && l.shelfLifeDays() <= 0) {
                errors.put(p + "shelfLifeDays", "Must be positive");
            }
            if (l.holdReason() != null && l.holdReason().length() > 500) {
                errors.put(p + "holdReason", "At most 500 characters");
            }
            String sku = l.sku() == null ? "" : l.sku().trim().toUpperCase(Locale.ROOT);
            String lotNo = l.lotNo() == null ? "" : l.lotNo().trim().toUpperCase(Locale.ROOT);
            if (!seen.add(sku + "|" + lotNo)) {
                errors.put(p + "lotNo", "Duplicate lot in this receipt; add the quantities together");
            }
            lines.add(new ReceiveCommand.Line(sku, lotNo, l.mfgDate(), l.expDate(), l.qtyGood(), l.qtyDamaged(),
                    l.bin() == null || l.bin().isBlank() ? null : l.bin().trim().toUpperCase(Locale.ROOT),
                    l.unitLandedCostMinor(), l.currency() == null ? null : l.currency().toUpperCase(Locale.ROOT),
                    l.shelfLifeDays(), l.holdReason() == null || l.holdReason().isBlank() ? null
                    : l.holdReason().trim()));
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Invalid receipt", errors);
        }
        return new ReceiveCommand(cmd.receiptRef().trim(), cmd.nodeId().trim().toUpperCase(Locale.ROOT),
                owner(cmd.ownerOrg()), cmd.inboundRef() == null || cmd.inboundRef().isBlank() ? null
                : cmd.inboundRef().trim(), lines);
    }

    private String owner(String requested) {
        return requested == null || requested.isBlank() ? settings.retailOrgId() : requested.trim();
    }

    private Node activeNode(String id) {
        String code = id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
        Node node = nodes.findById(code).orElseThrow(() -> new NotFoundException("inventory.node-not-found",
                "Unknown node " + id));
        if (!node.active()) {
            throw new InventoryRuleViolation("inventory.node-inactive", "Node " + code + " is not active");
        }
        return node;
    }
}
