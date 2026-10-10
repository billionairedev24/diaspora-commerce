package com.afrotransact.inventory.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.inventory.domain.FcAssignment;
import com.afrotransact.inventory.domain.FcAssignmentPolicy;
import com.afrotransact.inventory.domain.FcCandidate;
import com.afrotransact.inventory.domain.InboundBox;
import com.afrotransact.inventory.domain.InboundLine;
import com.afrotransact.inventory.domain.InboundShipment;
import com.afrotransact.inventory.domain.InboundSource;
import com.afrotransact.inventory.domain.InboundStatus;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.InventoryStateConflict;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.NodeKind;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.CarrierGateway;
import com.afrotransact.inventory.port.InboundShipmentRepository;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.UnitLabelClient;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InboundShipmentServiceImpl implements InboundShipmentService {

    static final String CONSUMER_RETAIL = "inventory-retail-shipments";
    static final int MAX_LINES = 200;
    static final int MAX_BOXES = 200;
    static final int MAX_COPIES_PER_LABEL = 1000;
    /** FBA units are the seller's: no landed cost on AfroTransact's books. */
    static final String FBA_COST_CURRENCY = "USD";
    private static final Logger LOG = LoggerFactory.getLogger(InboundShipmentServiceImpl.class);
    private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Set<String> RETAIL_IN_TRANSIT = Set.of("DEPARTED", "ARRIVED_PORT", "CUSTOMS_CLEARED",
            "DELIVERED_TO_FC");

    private final InboundShipmentRepository inbounds;
    private final NodeRepository nodes;
    private final PositionRepository positions;
    private final FcAssignmentPolicy fcPolicy;
    private final UnitLabelClient labels;
    private final CarrierGateway carriers;
    private final ReceivingService receiving;
    private final StockWriter writer;
    private final InventoryAccess access;
    private final InventorySettings settings;
    private final DomainEventPublisher events;
    private final InboxDeduplicator inbox;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    InboundShipmentServiceImpl(InboundShipmentRepository inbounds, NodeRepository nodes, PositionRepository positions,
                               FcAssignmentPolicy fcPolicy, UnitLabelClient labels, CarrierGateway carriers,
                               ReceivingService receiving, StockWriter writer, InventoryAccess access,
                               InventorySettings settings, DomainEventPublisher events, InboxDeduplicator inbox,
                               Clock clock) {
        this.inbounds = inbounds;
        this.nodes = nodes;
        this.positions = positions;
        this.fcPolicy = fcPolicy;
        this.labels = labels;
        this.carriers = carriers;
        this.receiving = receiving;
        this.writer = writer;
        this.access = access;
        this.settings = settings;
        this.events = events;
        this.inbox = inbox;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ plan

    @Override
    @Transactional
    public InboundShipment create(InboundCommands.CreatePlan c) {
        InventoryAccess.InboundActor who = access.inboundWriter(c.ownerOrg());
        if (who.orgId() == null) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Name the seller organisation",
                    Map.of("ownerOrg", "Required for staff"));
        }
        String owner = who.orgId();
        List<InboundLine> lines = planLines(c.lines(), owner);
        List<InboundBox> boxes = boxes(c.boxes(), lines, List.of());
        FcAssignment fc = assign(owner, lines, upper(c.nodeId()), blankToNull(c.storageZone()));
        Instant now = Instant.now(clock);
        InboundShipment plan = new InboundShipment(UUID.randomUUID(), inbounds.nextFbaNumber(), InboundSource.FBA,
                null, owner, fc.nodeId(), upper(c.nodeId()), fc.reason(), blankToNull(c.name()),
                InboundStatus.WORKING, c.shipFrom(), null, null, null, null, null, null, null, c.expectedArrival(), 0,
                lines, boxes, who.actor().subject(), now, null, null, now, 0);
        inbounds.insert(plan);
        publish(InventoryEvents.INBOUND_CREATED, plan);
        return plan;
    }

    @Override
    @Transactional(readOnly = true)
    public InboundShipment get(UUID id) {
        Optional<String> scope = access.inboundReadScope();
        InboundShipment s = inbounds.findById(id).orElseThrow(() -> notFound(id));
        visible(scope, s);
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InboundShipment> list(InboundShipmentRepository.InboundQuery q) {
        Optional<String> scope = access.inboundReadScope();
        return inbounds.search(new InboundShipmentRepository.InboundQuery(scope.orElse(blankToNull(q.ownerOrg())),
                upper(q.nodeId()), q.status(), q.source(), upper(q.sku()), Math.max(q.page(), 0),
                Math.clamp(q.size(), 1, 100)));
    }

    @Override
    @Transactional
    public InboundShipment updatePlan(UUID id, InboundCommands.CreatePlan c, Long expectedVersion) {
        InboundShipment s = writable(id, expectedVersion);
        List<InboundLine> lines = c.lines().isEmpty() ? s.lines() : planLines(c.lines(), s.ownerOrg(), s.lines());
        if (!c.lines().isEmpty()) {
            conflictsAt(s.nodeId(), s.ownerOrg(), lines);
        }
        List<InboundBox> boxes = boxes(c.boxes(), lines, s.boxes());
        return inbounds.update(s.replacePlan(lines, boxes, Instant.now(clock)));
    }

    // ------------------------------------------------------------------ labels and tracking

    @Override
    @Transactional
    public UnitLabelClient.RenderedLabels labels(UUID id, LabelKind kind, String format, Integer lineNo) {
        Optional<String> scope = access.inboundReadScope();
        InboundShipment s = inbounds.lockById(id).orElseThrow(() -> notFound(id));
        visible(scope, s);
        if (s.source() != InboundSource.FBA) {
            throw new InventoryRuleViolation("inventory.inbound-invalid-state", "Labels are printed for FBA plans");
        }
        String f = format == null ? "PDF" : format.trim().toUpperCase(Locale.ROOT);
        if (!f.equals("PDF") && !f.equals("ZPL")) {
            throw new InventoryRuleViolation("inventory.invalid-request", "PDF or ZPL",
                    Map.of("format", "PDF or ZPL"));
        }
        List<UnitLabelClient.LabelSpec> specs = new ArrayList<>();
        if (kind == LabelKind.BOX) {
            if (s.boxes().isEmpty()) {
                throw new InventoryRuleViolation("inventory.inbound-no-boxes", "Add the boxes of " + s.number()
                        + " first; each box gets a carton label (LPN)");
            }
            Node node = nodes.findById(s.nodeId()).orElseThrow();
            for (InboundBox b : s.boxes()) {
                specs.add(new UnitLabelClient.LabelSpec("CUSTOM", b.lpn(), "SHIP TO AFROTRANSACT " + node.id(),
                        s.number(), List.of("Box " + b.boxNo() + " of " + s.boxes().size(), b.units() + " units, "
                        + b.contents().size() + " SKU" + (b.contents().size() == 1 ? "" : "s"),
                        "Owner " + s.ownerOrg(), node.name()), 1, null));
            }
        } else {
            InboundShipment withCodes = ensureUnitLabels(s);
            for (InboundLine l : withCodes.lines()) {
                if (lineNo != null && l.lineNo() != lineNo) {
                    continue;
                }
                if (l.unitLabel() == null || l.expectedQty() == 0) {
                    continue;
                }
                int left = l.expectedQty();
                while (left > 0) {
                    int copies = Math.min(left, MAX_COPIES_PER_LABEL);
                    specs.add(new UnitLabelClient.LabelSpec("UNIT", l.unitLabel(), "AFROTRANSACT UNIT LABEL",
                            l.sku(), List.of(l.atin() == null ? "" : "ATIN " + l.atin(), s.number()), copies, null));
                    left -= copies;
                }
            }
            if (specs.isEmpty()) {
                throw new InventoryRuleViolation("inventory.invalid-request", "No unit labels to print"
                        + (lineNo == null ? "" : " on line " + lineNo), Map.of("lineNo", "No such line"));
            }
        }
        return labels.render(specs, f);
    }

    @Override
    @Transactional
    public TrackingResult track(UUID id, String carrier, String trackingNumber) {
        InboundShipment s = writable(id, null, true);
        String c = carrier == null ? "" : carrier.trim().toLowerCase(Locale.ROOT);
        String t = trackingNumber == null ? "" : trackingNumber.trim().toUpperCase(Locale.ROOT);
        if (c.isEmpty() || t.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Carrier and tracking number are required",
                    Map.of("trackingNumber", "Required"));
        }
        boolean registered;
        try {
            carriers.registerTracking(c, t, s.number());
            registered = true;
        } catch (CarrierGateway.CarrierException e) {
            LOG.warn("carrier-gateway did not register {} {} for {}: {}", c, t, s.number(), e.getMessage());
            registered = false;
        }
        InboundShipment saved = inbounds.update(s.track(c, t, InboundShipment.TRACKING_SELLER, null, null, null, null,
                Instant.now(clock)));
        return new TrackingResult(saved, registered);
    }

    @Override
    @Transactional
    public InboundShipment buyLabel(UUID id, InboundCommands.BuyLabel c) {
        InboundShipment s = writable(id, null, true);
        Map<String, String> errors = new LinkedHashMap<>();
        if (c.carrier() == null || c.carrier().isBlank()) {
            errors.put("carrier", "Required (usps, ups, fedex, dhl)");
        }
        if (c.service() == null || c.service().isBlank()) {
            errors.put("service", "Required (service code from a rate quote)");
        }
        if (s.shipFrom() == null || !s.shipFrom().complete()) {
            errors.put("shipFrom", "A complete ship-from address is required");
        }
        if (s.boxes().isEmpty() || s.boxes().stream().anyMatch(b -> b.weightLb() == null)) {
            errors.put("boxes", "Every box needs a weight");
        }
        PostalAddress to = shipTo(s);
        if (!to.complete()) {
            errors.put("nodeId", "The fulfilment centre's street address is not set yet; enter tracking instead");
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.inbound-label-invalid", "The label cannot be bought", errors);
        }
        CarrierGateway.PurchasedLabel label;
        try {
            label = carriers.buyLabel(new CarrierGateway.LabelPurchase(c.carrier().trim().toLowerCase(Locale.ROOT),
                    c.service().trim(), s.number(), s.ownerOrg(), s.shipFrom(), to,
                    s.boxes().stream().map(b -> new CarrierGateway.Parcel(b.weightLb(), b.lengthIn(), b.widthIn(),
                            b.heightIn())).toList(), c.shipDate()), "inbound-" + s.id() + "-label");
        } catch (CarrierGateway.CarrierException e) {
            if (e.retryable()) {
                throw new InventoryUpstreamUnavailable("inventory.carrier-unavailable", "carrier-gateway is "
                        + "unavailable; retry shortly: " + e.getMessage());
            }
            throw new InventoryRuleViolation("inventory.carrier-rejected", "The carrier refused the label: "
                    + e.getMessage(), Map.of("carrier", e.code()));
        }
        return inbounds.update(s.track(label.carrier(), label.trackingNumber(),
                InboundShipment.TRACKING_CARRIER_GATEWAY, label.labelId(), label.costMinor(), label.currency(),
                label.documentUrl(), Instant.now(clock)));
    }

    // ------------------------------------------------------------------ ship, receive, close

    @Override
    @Transactional
    public InboundShipment ship(UUID id, Long expectedVersion) {
        InboundShipment s = writable(id, expectedVersion);
        InboundShipment shipped = s.ship(Instant.now(clock));
        requireBoxesCoverPlan(shipped);
        return addInbound(shipped, actorSubject());
    }

    @Override
    @Transactional
    public ReceiptOutcome receive(UUID id, InboundCommands.ReceivePlan c, String idempotencyKey) {
        access.require(InventoryPermissions.RECEIVE);
        InboundShipment s = inbounds.findById(id).orElseThrow(() -> notFound(id));
        if (c.lines().isEmpty() && c.boxesReceived().isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Count at least one SKU or box",
                    Map.of("lines", "Required"));
        }
        if (s.status() != InboundStatus.SHIPPED && s.status() != InboundStatus.RECEIVING) {
            throw new InventoryStateConflict("inventory.inbound-invalid-state", "Shipment " + s.number() + " is "
                    + s.status() + "; mark it shipped before receiving");
        }
        ReceiptResult result = null;
        if (!c.lines().isEmpty()) {
            String receiptRef = s.number() + "-" + shortHash(idempotencyKey);
            List<ReceiveCommand.Line> lines = new ArrayList<>();
            for (int i = 0; i < c.lines().size(); i++) {
                InboundCommands.ReceiveLine l = c.lines().get(i);
                String sku = resolveSku(s, l, i);
                lines.add(new ReceiveCommand.Line(sku, l.lotNo() == null || l.lotNo().isBlank() ? receiptRef
                        : l.lotNo(), l.mfgDate(), l.expDate(), l.qtyGood(), l.qtyDamaged(), l.bin(), 0,
                        FBA_COST_CURRENCY, null, l.holdReason()));
            }
            result = receiving.receive(new ReceiveCommand(receiptRef, s.nodeId(), s.ownerOrg(), s.number(), lines),
                    idempotencyKey);
        }
        InboundShipment current = inbounds.lockById(id).orElseThrow();
        if (!c.boxesReceived().isEmpty()) {
            Set<String> known = new HashSet<>(current.boxes().stream().map(InboundBox::lpn).toList());
            List<String> lpns = c.boxesReceived().stream().map(v -> v.trim().toUpperCase(Locale.ROOT)).toList();
            List<String> unknown = lpns.stream().filter(v -> !known.contains(v)).toList();
            if (!unknown.isEmpty()) {
                throw new InventoryRuleViolation("inventory.inbound-box-unknown", "Not a box of " + s.number() + ": "
                        + String.join(", ", unknown), Map.of("boxesReceived", "Unknown LPN"));
            }
            current = inbounds.update(current.boxesReceived(lpns, Instant.now(clock)));
        }
        return new ReceiptOutcome(current, result);
    }

    @Override
    @Transactional
    public InboundShipment close(UUID id) {
        Actor actor = access.require(InventoryPermissions.RECEIVE);
        InboundShipment s = inbounds.lockById(id).orElseThrow(() -> notFound(id));
        InboundShipment closed = settleInbound(s, s.close(Instant.now(clock)), actor.subject(), "Inbound closed: "
                + "units not received");
        publish(InventoryEvents.INBOUND_CLOSED, closed);
        return closed;
    }

    @Override
    @Transactional
    public InboundShipment cancel(UUID id) {
        Actor actor = access.actorForCancel();
        InboundShipment s = inbounds.lockById(id).orElseThrow(() -> notFound(id));
        if (!access.mayActFor(actor, s.ownerOrg())) {
            throw notFound(id);
        }
        InboundShipment cancelled = settleInbound(s, s.cancel(Instant.now(clock)), actor.subject(),
                "Inbound cancelled");
        publish(InventoryEvents.INBOUND_CANCELLED, cancelled);
        return cancelled;
    }

    @Override
    public PostalAddress shipTo(InboundShipment s) {
        return nodes.address(s.nodeId()).orElse(new PostalAddress("AfroTransact " + s.nodeId(), null, null, null,
                null, null, null, null));
    }

    // ------------------------------------------------------------------ retail ASN (S226)

    @Override
    @Transactional
    public void retailShipmentChanged(UUID eventId, InboundCommands.RetailShipmentNotice n) {
        if (!inbox.firstDelivery(CONSUMER_RETAIL, eventId) || n.reference() == null || n.stage() == null) {
            return;
        }
        Optional<InboundShipment> existing = inbounds.lockByExternalRef(InboundSource.RETAIL_ASN, n.reference());
        if ("RECEIVED".equals(n.stage())) {
            existing.filter(s -> s.status() == InboundStatus.SHIPPED || s.status() == InboundStatus.RECEIVING)
                    .ifPresent(s -> publish(InventoryEvents.INBOUND_CLOSED, settleInbound(s, s.close(Instant.now(
                            clock)), "retail-service", "Retail shipment received: units not received")));
            return;
        }
        if (!RETAIL_IN_TRANSIT.contains(n.stage()) || existing.isPresent()) {
            return;
        }
        String nodeId = upper(n.nodeId());
        Optional<Node> node = nodeId == null ? Optional.empty() : nodes.findById(nodeId);
        if (node.isEmpty() || n.lines().isEmpty()) {
            LOG.warn("Retail shipment {} names unknown node {} or no lines; no inbound notice", n.reference(),
                    n.nodeId());
            return;
        }
        Map<String, Integer> bySku = new LinkedHashMap<>();
        n.lines().stream().filter(l -> l.sku() != null && l.qty() > 0)
                .forEach(l -> bySku.merge(l.sku().trim().toUpperCase(Locale.ROOT), l.qty(), Integer::sum));
        List<InboundLine> lines = new ArrayList<>();
        bySku.forEach((sku, qty) -> lines.add(new InboundLine(lines.size() + 1, sku, null, null, qty, 0, 0, 0)));
        Instant now = Instant.now(clock);
        InboundShipment notice = new InboundShipment(UUID.randomUUID(), n.reference(), InboundSource.RETAIL_ASN,
                n.reference(), settings.retailOrgId(), node.get().id(), null, "Purchase order destination", null,
                InboundStatus.WORKING, null, null, null, null, null, null, null, null, n.eta(), 0, lines, List.of(),
                "retail-service", now, null, null, now, 0);
        inbounds.insert(notice);
        addInbound(inbounds.findById(notice.id()).orElseThrow().ship(now), "retail-service");
    }

    // ------------------------------------------------------------------ helpers

    /** Adds the shipped plan's expected units to the positions' inbound bucket and stores the shipment. */
    private InboundShipment addInbound(InboundShipment shipped, String actor) {
        StockChanges changes = writer.begin(actor);
        TreeSet<String> skus = new TreeSet<>(shipped.lines().stream().map(InboundLine::sku).toList());
        skus.forEach(sku -> positions.ensureExists(sku, shipped.nodeId(), shipped.ownerOrg(), changes.now()));
        positions.lockBySkus(skus).forEach(changes::track);
        for (InboundLine l : shipped.lines()) {
            StockPosition p = changes.requirePosition(l.sku(), shipped.nodeId());
            if (!p.ownerOrg().equals(shipped.ownerOrg())) {
                throw new ConflictException("inventory.owner-mismatch", "SKU " + l.sku() + " at " + shipped.nodeId()
                        + " belongs to another organisation; FBA stock is never mixed");
            }
            if (l.inboundOpen() > 0) {
                changes.apply(MovementType.INBOUND, p.adjustInbound(l.inboundOpen()), null, l.inboundOpen(),
                        shipped.number(), "Inbound shipment " + shipped.number() + " on its way");
            }
        }
        writer.flush(changes);
        InboundShipment saved = inbounds.update(shipped);
        publish(InventoryEvents.INBOUND_SHIPPED, saved);
        return saved;
    }

    /** Takes the units still open on {@code before} off inbound and stores {@code after}. */
    private InboundShipment settleInbound(InboundShipment before, InboundShipment after, String actor, String why) {
        List<InboundLine> open = before.lines().stream().filter(l -> l.inboundOpen() > 0).toList();
        if (!open.isEmpty()) {
            StockChanges changes = writer.begin(actor);
            positions.lockBySkus(new TreeSet<>(open.stream().map(InboundLine::sku).toList())).forEach(changes::track);
            for (InboundLine l : open) {
                Optional<StockPosition> p = changes.position(l.sku(), before.nodeId());
                int cancel = p.map(pos -> Math.min(pos.inbound(), l.inboundOpen())).orElse(0);
                if (cancel > 0) {
                    changes.apply(MovementType.INBOUND, p.get().adjustInbound(-cancel), null, cancel,
                            before.number(), why);
                }
            }
            writer.flush(changes);
        }
        return inbounds.update(after);
    }

    private InboundShipment ensureUnitLabels(InboundShipment s) {
        boolean missing = s.lines().stream().anyMatch(l -> l.unitLabel() == null && l.atin() != null);
        if (!missing) {
            return s;
        }
        List<InboundLine> lines = new ArrayList<>();
        for (InboundLine l : s.lines()) {
            lines.add(l.unitLabel() == null && l.atin() != null ? l.withUnitLabel(code(l.atin(), s.ownerOrg())
                    .orElse(null)) : l);
        }
        return inbounds.update(s.withLines(lines, Instant.now(clock)));
    }

    private Optional<String> code(String atin, String owner) {
        try {
            return labels.unitLabelCode(atin, owner);
        } catch (UnitLabelClient.LabelServiceException e) {
            if (e.retryable()) {
                throw new InventoryUpstreamUnavailable("inventory.catalog-unavailable", "catalog-service is "
                        + "unavailable for unit labels; retry shortly");
            }
            throw new InventoryRuleViolation("inventory.invalid-atin", "catalog has no unit label for " + atin
                    + ": " + e.getMessage(), Map.of("atin", "Unknown variant ATIN or not yours"));
        }
    }

    /** With an explicit SKU the plan does not need catalog: the AT-FN code is fetched again when labels print. */
    private String codeIfAvailable(String atin, String owner) {
        try {
            return code(atin, owner).orElse(null);
        } catch (InventoryUpstreamUnavailable e) {
            LOG.warn("Unit label of {} for {} not issued yet: {}", atin, owner, e.getMessage());
            return null;
        }
    }

    private List<InboundLine> planLines(List<InboundCommands.PlanLine> input, String owner) {
        return planLines(input, owner, List.of());
    }

    private List<InboundLine> planLines(List<InboundCommands.PlanLine> input, String owner,
                                        List<InboundLine> previous) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (input.isEmpty() || input.size() > MAX_LINES) {
            errors.put("lines", "1 to " + MAX_LINES + " SKUs");
        }
        Map<String, String> knownCodes = new LinkedHashMap<>();
        previous.forEach(l -> {
            if (l.atin() != null && l.unitLabel() != null) {
                knownCodes.put(l.atin(), l.unitLabel());
            }
        });
        List<InboundLine> lines = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            InboundCommands.PlanLine l = input.get(i);
            String p = "lines[" + i + "].";
            String atin = upper(l.atin());
            if (atin == null || !atin.matches("AT[0-9A-Z]{8}")) {
                errors.put(p + "atin", "A variant ATIN (AT + 8 letters or digits)");
                continue;
            }
            if (l.qty() < 1 || l.qty() > 100_000) {
                errors.put(p + "qty", "1 to 100000 units");
            }
            String sku = upper(l.sku());
            String code = knownCodes.get(atin);
            if (code == null) {
                code = sku == null ? code(atin, owner).orElse(null) : codeIfAvailable(atin, owner);
                if (code != null) {
                    knownCodes.put(atin, code);
                }
            }
            if (sku == null) {
                if (code == null) {
                    errors.put(p + "sku", "This variant keeps its manufacturer barcode; give the inventory SKU");
                    continue;
                }
                sku = code;
            }
            if (sku.length() > 64 || !sku.matches("[A-Z0-9][A-Z0-9._/-]*")) {
                errors.put(p + "sku", "Letters, digits, . _ / - (max 64)");
            }
            if (!skus.add(sku)) {
                errors.put(p + "sku", "Duplicate SKU in this plan; add the quantities together");
            }
            lines.add(new InboundLine(lines.size() + 1, sku, atin, code, Math.max(l.qty(), 0), 0, 0, 0));
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Invalid inbound plan", errors);
        }
        return lines;
    }

    private List<InboundBox> boxes(List<InboundCommands.BoxInput> input, List<InboundLine> lines,
                                   List<InboundBox> previous) {
        if (input.isEmpty()) {
            return previous.stream().filter(b -> b.contents().stream()
                    .allMatch(item -> lines.stream().anyMatch(l -> l.sku().equals(item.sku())))).toList();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (input.size() > MAX_BOXES) {
            errors.put("boxes", "At most " + MAX_BOXES + " boxes");
        }
        Map<String, Integer> boxed = new LinkedHashMap<>();
        List<InboundBox> boxes = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            InboundCommands.BoxInput b = input.get(i);
            String p = "boxes[" + i + "].";
            if (b.contents().isEmpty()) {
                errors.put(p + "contents", "At least one SKU");
            }
            List<InboundBox.Item> items = new ArrayList<>();
            for (InboundCommands.BoxItem item : b.contents()) {
                String sku = upper(item.sku());
                if (sku == null || lines.stream().noneMatch(l -> l.sku().equals(sku))) {
                    errors.put(p + "contents", "SKU " + item.sku() + " is not on the plan");
                    continue;
                }
                if (item.qty() < 1) {
                    errors.put(p + "contents", "Quantities must be positive");
                    continue;
                }
                boxed.merge(sku, item.qty(), Integer::sum);
                items.add(new InboundBox.Item(sku, item.qty()));
            }
            if (b.weightLb() != null && (b.weightLb().signum() <= 0 || b.weightLb().intValue() > 150)) {
                errors.put(p + "weightLb", "Between 0 and 150 lb");
            }
            String lpn = previous.size() > i ? previous.get(i).lpn() : newLpn();
            boxes.add(new InboundBox(i + 1, lpn, items, b.weightLb(), b.lengthIn(), b.widthIn(), b.heightIn(),
                    null));
        }
        for (InboundLine l : lines) {
            if (boxed.getOrDefault(l.sku(), 0) > l.expectedQty()) {
                errors.put("boxes", "More units of " + l.sku() + " in boxes (" + boxed.get(l.sku()) + ") than on the "
                        + "plan (" + l.expectedQty() + ")");
            }
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Invalid boxes", errors);
        }
        return boxes;
    }

    private void requireBoxesCoverPlan(InboundShipment s) {
        if (s.boxes().isEmpty()) {
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (InboundLine l : s.lines()) {
            int boxed = s.boxes().stream().flatMap(b -> b.contents().stream())
                    .filter(item -> item.sku().equals(l.sku())).mapToInt(InboundBox.Item::qty).sum();
            if (boxed != l.expectedQty()) {
                errors.put("boxes", l.sku() + ": " + boxed + " of " + l.expectedQty() + " units are in boxes");
            }
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.inbound-boxes-incomplete", "Pack every planned unit in a box "
                    + "before shipping", errors);
        }
    }

    private FcAssignment assign(String owner, List<InboundLine> lines, String requested, String zone) {
        List<String> skus = lines.stream().map(InboundLine::sku).toList();
        List<StockPosition> existing = positions.findBySkus(skus, null);
        Map<String, Long> held = nodes.unitsHeldByNode();
        List<FcCandidate> candidates = new ArrayList<>();
        for (Node n : nodes.findAll()) {
            if (!n.active() || n.kind() != NodeKind.FULFILMENT_CENTRE) {
                continue;
            }
            List<String> foreign = existing.stream().filter(p -> p.nodeId().equals(n.id())
                    && !p.ownerOrg().equals(owner)).map(StockPosition::sku).sorted().toList();
            int own = (int) existing.stream().filter(p -> p.nodeId().equals(n.id()) && p.ownerOrg().equals(owner)
                    && p.onHand() + p.inbound() + p.damaged() > 0).count();
            candidates.add(new FcCandidate(n.id(), n.temperatureZones(), foreign, own, held.getOrDefault(n.id(),
                    0L)));
        }
        return fcPolicy.assign(candidates, requested, zone);
    }

    private void conflictsAt(String nodeId, String owner, List<InboundLine> lines) {
        for (StockPosition p : positions.findBySkus(lines.stream().map(InboundLine::sku).toList(), null)) {
            if (p.nodeId().equals(nodeId) && !p.ownerOrg().equals(owner)) {
                throw new ConflictException("inventory.owner-mismatch", "SKU " + p.sku() + " at " + nodeId
                        + " belongs to another organisation; FBA stock is never mixed");
            }
        }
    }

    private String resolveSku(InboundShipment s, InboundCommands.ReceiveLine l, int i) {
        if (l.unitLabel() != null && !l.unitLabel().isBlank()) {
            String code = l.unitLabel().trim().toUpperCase(Locale.ROOT);
            return s.lineByUnitLabel(code).map(InboundLine::sku).orElseThrow(() -> new InventoryRuleViolation(
                    "inventory.inbound-label-unknown", "Unit label " + code + " is not on " + s.number()
                    + "; units of other sellers are never received here", Map.of("lines[" + i + "].unitLabel",
                    "Not on this shipment")));
        }
        String sku = upper(l.sku());
        if (sku == null) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Scan the unit label or give the SKU",
                    Map.of("lines[" + i + "].sku", "Required"));
        }
        return sku;
    }

    private InboundShipment writable(UUID id, Long expectedVersion) {
        return writable(id, expectedVersion, false);
    }

    /** Locks a plan the caller may change: own org for sellers; FBA only. */
    private InboundShipment writable(UUID id, Long expectedVersion, boolean trackingChange) {
        InventoryAccess.InboundActor who = access.inboundWriter(null);
        InboundShipment s = inbounds.lockById(id).orElseThrow(() -> notFound(id));
        if (who.orgId() != null && !who.orgId().equals(s.ownerOrg())) {
            throw notFound(id);
        }
        if (s.source() != InboundSource.FBA) {
            throw new InventoryStateConflict("inventory.inbound-invalid-state", s.number() + " is a retail ASN; "
                    + "retail-service manages it");
        }
        if (expectedVersion != null && expectedVersion != s.version()) {
            throw new ConflictException("inventory.version-mismatch", "Shipment " + s.number() + " is at version "
                    + s.version() + ", not " + expectedVersion);
        }
        if (!trackingChange && s.status() != InboundStatus.WORKING) {
            throw new InventoryStateConflict("inventory.inbound-invalid-state", "Shipment " + s.number() + " is "
                    + s.status() + "; the plan can no longer change");
        }
        return s;
    }

    private void visible(Optional<String> scope, InboundShipment s) {
        if (scope.isPresent() && !scope.get().equals(s.ownerOrg())) {
            throw notFound(s.id());
        }
    }

    private void publish(String type, InboundShipment s) {
        events.publish(type, InventoryEvents.AGGREGATE_INBOUND, s.id().toString(),
                InboundEventPayloads.InboundShipmentEvent.of(s));
    }

    private String actorSubject() {
        return access.currentSubject();
    }

    private String newLpn() {
        StringBuilder sb = new StringBuilder("LPN");
        for (int i = 0; i < 8; i++) {
            sb.append(BASE36.charAt(random.nextInt(BASE36.length())));
        }
        return sb.toString();
    }

    /** 8 hex characters of the idempotency key: the same request always gets the same receipt reference. */
    private static String shortHash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String upper(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("inventory.inbound-not-found", "Inbound shipment " + id + " not found");
    }
}
