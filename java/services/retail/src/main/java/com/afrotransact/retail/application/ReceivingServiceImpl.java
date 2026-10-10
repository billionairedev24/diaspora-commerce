package com.afrotransact.retail.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.retail.domain.CostAllocator;
import com.afrotransact.retail.domain.Discrepancy;
import com.afrotransact.retail.domain.GlPostingRules;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.LandedCost;
import com.afrotransact.retail.domain.LandedCostCalculator;
import com.afrotransact.retail.domain.LandedCostInput;
import com.afrotransact.retail.domain.LandedCostLine;
import com.afrotransact.retail.domain.PostedLot;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.QaResult;
import com.afrotransact.retail.domain.ReceiptLine;
import com.afrotransact.retail.domain.ReceiptStatus;
import com.afrotransact.retail.domain.ReceiptValidation;
import com.afrotransact.retail.domain.ReceiptValidator;
import com.afrotransact.retail.domain.ReceivedQty;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.RetailStateConflict;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.port.InventoryClient;
import com.afrotransact.retail.port.InventoryLot;
import com.afrotransact.retail.port.InventoryReceipt;
import com.afrotransact.retail.port.InventoryReceiptResult;
import com.afrotransact.retail.port.InventoryRejectedException;
import com.afrotransact.retail.port.ItemRepository;
import com.afrotransact.retail.port.LotRegistry;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.PurchaseOrderRepository;
import com.afrotransact.retail.port.ReceiptRepository;
import com.afrotransact.retail.port.ReferenceIssuer;
import com.afrotransact.retail.port.ShipmentRepository;
import com.afrotransact.retail.port.SupplierRepository;
import com.afrotransact.retail.port.WarehouseCalendar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
class ReceivingServiceImpl implements ReceivingService {

    static final String SELLABLE = "SELLABLE";
    static final String QUARANTINE = "QUARANTINE";

    private final ReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final ShipmentRepository shipments;
    private final SupplierRepository suppliers;
    private final ItemRepository items;
    private final LotRegistry lots;
    private final ReferenceIssuer refs;
    private final ReceiptValidator validator;
    private final LandedCostCalculator landedCost;
    private final CostAllocator allocator;
    private final GlPostingRules gl;
    private final JournalPoster journal;
    private final InventoryClient inventory;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final RetailSettings settings;
    private final TransactionOperations tx;
    private final WarehouseCalendar calendar;
    private final Clock clock;

    ReceivingServiceImpl(ReceiptRepository receipts, PurchaseOrderRepository orders, ShipmentRepository shipments,
                         SupplierRepository suppliers, ItemRepository items, LotRegistry lots, ReferenceIssuer refs,
                         ReceiptValidator validator, LandedCostCalculator landedCost, CostAllocator allocator,
                         GlPostingRules gl, JournalPoster journal, InventoryClient inventory,
                         PermissionChecker permissions, DomainEventPublisher events, RetailSettings settings,
                         TransactionOperations tx, WarehouseCalendar calendar, Clock clock) {
        this.receipts = receipts;
        this.orders = orders;
        this.shipments = shipments;
        this.suppliers = suppliers;
        this.items = items;
        this.lots = lots;
        this.refs = refs;
        this.validator = validator;
        this.landedCost = landedCost;
        this.allocator = allocator;
        this.gl = gl;
        this.journal = journal;
        this.inventory = inventory;
        this.permissions = permissions;
        this.events = events;
        this.settings = settings;
        this.tx = tx;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Override
    @Transactional
    public GoodsReceipt open(OpenReceiptCommand c) {
        Actor actor = permissions.require(RetailPermissions.RECEIVE);
        Instant now = Instant.now(clock);
        GoodsReceipt opened;
        if (c.shipmentId() != null) {
            InboundShipment s = shipments.findById(c.shipmentId()).orElseThrow(() -> new RetailRuleViolation(
                    "retail.shipment-not-found", "Shipment " + c.shipmentId() + " not found"));
            LocalDate today = calendar.today(s.destinationNodeId());
            receipts.findActiveByShipment(s.id()).ifPresent(existing -> {
                throw new ConflictException("retail.receipt-exists", "Receipt " + existing.number()
                        + " is already open for " + s.reference());
            });
            PurchaseOrder po = orders.findById(s.poId()).orElseThrow();
            opened = GoodsReceipt.forShipment(UUID.randomUUID(), refs.nextReceiptNumber(today), po, s, c.dock(),
                    actor.subject(), now);
        } else if (c.poId() != null) {
            PurchaseOrder po = orders.findById(c.poId()).orElseThrow(() -> new RetailRuleViolation(
                    "retail.po-not-found", "Purchase order " + c.poId() + " not found"));
            LocalDate today = calendar.today(po.destinationNodeId());
            opened = GoodsReceipt.forPurchaseOrder(UUID.randomUUID(), refs.nextReceiptNumber(today), po, c.dock(),
                    actor.subject(), now);
        } else {
            throw new RetailRuleViolation("retail.receipt-invalid", "Receive against a shipment or a purchase order");
        }
        GoodsReceipt saved = receipts.insert(opened);
        events.publish(RetailEvents.RECEIPT_OPENED, RetailEvents.AGGREGATE, saved.id().toString(),
                Map.of("receiptId", saved.id().toString(), "number", saved.number(), "poNumber", saved.poNumber(),
                        "nodeId", saved.nodeId()));
        return saved;
    }

    @Override
    @Transactional
    public GoodsReceipt count(UUID id, List<ReceiptLineInput> input, Long expectedVersion) {
        permissions.require(RetailPermissions.RECEIVE);
        GoodsReceipt r = find(id, expectedVersion);
        List<ReceiptLine> counted = new ArrayList<>();
        for (ReceiptLineInput in : input == null ? List.<ReceiptLineInput>of() : input) {
            ReceiptLine template = r.lines().stream().filter(l -> l.lineNo() == in.lineNo()).findFirst()
                    .or(() -> r.lines().stream().filter(l -> l.poLineNo() == in.poLineNo()).findFirst())
                    .orElseThrow(() -> new RetailRuleViolation("retail.receipt-invalid", "Line " + in.lineNo()
                            + " is not on receipt " + r.number()));
            counted.add(new ReceiptLine(in.lineNo(), template.poLineNo(), template.asnLineNo(), template.itemId(),
                    template.sku(), template.expectedQty(), template.asnLotNo(), in.lotNo(), in.mfgDate(),
                    in.expDate(), in.qtyGood(), in.qtyDamaged(), in.bin(), in.qa(), in.scanned(), false));
        }
        return receipts.update(r.count(counted, Instant.now(clock)));
    }

    @Override
    @Transactional
    public GoodsReceipt updateCharges(UUID id, List<LandedCharge> charges, Long expectedVersion) {
        permissions.require(RetailPermissions.FINANCE);
        GoodsReceipt r = find(id, expectedVersion);
        return receipts.update(r.updateCharges(charges, Instant.now(clock)));
    }

    @Override
    @Transactional
    public GoodsReceipt approveOverReceipt(UUID id, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.PO_APPROVE);
        GoodsReceipt r = find(id, expectedVersion);
        return receipts.update(r.approveOverReceipt(actor.subject(), Instant.now(clock)));
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptValidation validate(UUID id) {
        permissions.require(RetailPermissions.RECEIVE);
        GoodsReceipt r = find(id, null);
        return validation(r, itemsOf(r));
    }

    @Override
    public GoodsReceipt complete(UUID id) {
        Actor actor = permissions.require(RetailPermissions.RECEIVE);
        GoodsReceipt prepared = Objects.requireNonNull(tx.execute(s -> prepare(id)));
        if (prepared.status() == ReceiptStatus.COMPLETED) {
            return prepared;
        }
        Map<UUID, RetailItem> byId = itemsOf(prepared);
        InventoryReceiptResult result;
        try {
            // QA-failed batches carry holdReason and arrive in quarantine: no separate quarantine call (S226).
            result = inventory.receive(inventoryRequest(prepared, byId), "retail-" + prepared.id());
        } catch (InventoryRejectedException e) {
            tx.executeWithoutResult(s -> reopen(id));
            throw new RetailRuleViolation("retail.inventory-rejected", "Inventory rejected the receipt: "
                    + e.getMessage(), e.fieldErrors());
        }
        return Objects.requireNonNull(tx.execute(s -> finish(id, result, actor)));
    }

    @Override
    @Transactional
    public GoodsReceipt resolveClaim(UUID id, String claimRef, String resolution) {
        Actor actor = permissions.require(RetailPermissions.PO_WRITE);
        GoodsReceipt r = find(id, null);
        GoodsReceipt updated = receipts.update(r.resolveClaim(claimRef, resolution, Instant.now(clock)));
        events.publish(RetailEvents.CLAIM_RESOLVED, RetailEvents.AGGREGATE, r.id().toString(),
                Map.of("receiptId", r.id().toString(), "number", r.number(), "claimRef", claimRef,
                        "resolution", resolution, "actor", actor.subject()));
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public GoodsReceipt get(UUID id) {
        permissions.require(RetailPermissions.READ);
        return find(id, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GoodsReceipt> list(ReceiptStatus status, UUID poId, int page, int size) {
        permissions.require(RetailPermissions.READ);
        return receipts.page(status, poId, Math.max(0, page), Math.clamp(size, 1, 200));
    }

    /** OPEN → POSTING: validate, cost, raise discrepancies and reserve the lot numbers. */
    private GoodsReceipt prepare(UUID id) {
        GoodsReceipt r = find(id, null);
        if (r.status() != ReceiptStatus.OPEN) {
            return r;
        }
        PurchaseOrder po = orders.findById(r.poId()).orElseThrow();
        if (!po.status().receivable()) {
            throw new RetailStateConflict("retail.po-not-receivable", "PO " + po.number() + " is " + po.status());
        }
        Map<UUID, RetailItem> byId = itemsOf(r);
        ReceiptValidation v = validation(r, byId);
        if (!v.ok()) {
            throw new RetailRuleViolation("retail.receipt-invalid", "Fix the highlighted lines first",
                    v.fieldErrors());
        }
        LandedCost cost = cost(r, po, byId);
        List<Discrepancy> found = validator.discrepancies(r, v);
        lots.register(r.id(), lotKeys(r));
        return receipts.update(r.startPosting(cost, found, Instant.now(clock)));
    }

    private void reopen(UUID id) {
        GoodsReceipt r = find(id, null);
        if (r.status() == ReceiptStatus.POSTING) {
            lots.release(r.id());
            receipts.update(r.reopen(Instant.now(clock)));
        }
    }

    /** POSTING → COMPLETED: PO quantities, shipment, scorecard, GL, events. */
    private GoodsReceipt finish(UUID id, InventoryReceiptResult result, Actor actor) {
        GoodsReceipt r = find(id, null);
        if (r.status() == ReceiptStatus.COMPLETED) {
            return r;
        }
        Instant now = Instant.now(clock);
        String by = actor.subject();
        PurchaseOrder po = orders.findById(r.poId()).orElseThrow();
        LandedCost cost = r.landedCost();
        long sellable = 0;
        long quarantined = 0;
        List<ReceivedQty> received = new ArrayList<>();
        List<PostedLot> posted = new ArrayList<>();
        long defective = 0;
        for (ReceiptLine line : r.lines()) {
            if (line.received() == 0) {
                continue;
            }
            received.add(new ReceivedQty(line.poLineNo(), line.qtyGood(), line.qtyDamaged()));
            LandedCostLine lc = cost.line(line.lineNo());
            List<Long> split = allocator.allocate(lc.totalMinor(),
                    List.of(BigDecimal.valueOf(line.qtyGood()), BigDecimal.valueOf(line.qtyDamaged())));
            boolean goodHeld = line.qa() == QaResult.FAIL
                    || lotOf(result, line).map(l -> !SELLABLE.equals(l.status())).orElse(false);
            if (goodHeld) {
                quarantined += split.get(0);
            } else {
                sellable += split.get(0);
            }
            quarantined += split.get(1);
            defective += line.qtyDamaged() + (line.qa() == QaResult.FAIL ? line.qtyGood() : 0);
        }
        for (InventoryLot l : result.lots()) {
            boolean qaFailed = r.lines().stream().anyMatch(line -> line.qa() == QaResult.FAIL
                    && l.sku().equals(line.sku()) && l.lotNo().equals(line.lotNo()));
            posted.add(new PostedLot(l.id(), l.sku(), l.lotNo(), l.qtyOnHand(), qaFailed ? QUARANTINE : l.status(),
                    l.unitLandedCostMinor()));
        }
        boolean inTransit = r.shipmentId() != null;
        JournalEntry entry = journal.post(JournalSource.GOODS_RECEIPT, r.number(), "Goods received at " + r.nodeId()
                + " (" + po.number() + ") — landed cost capitalised",
                gl.goodsReceived(sellable, quarantined, cost.fobMinor(), cost.dutyMinor() + cost.chargesMinor(),
                        inTransit), by);

        PurchaseOrder updatedPo = orders.update(po.receive(received, r.number(), by, now));
        LocalDate due;
        if (r.shipmentId() != null) {
            InboundShipment s = shipments.findById(r.shipmentId()).orElseThrow();
            InboundShipment done = shipments.update(s.markReceived(by, now));
            due = s.eta();
            events.publish(RetailEvents.SHIPMENT_STAGE_CHANGED, RetailEvents.AGGREGATE, done.id().toString(),
                    RetailEventPayloads.ShipmentEvent.of(done));
        } else {
            due = po.expectedDate();
        }
        Supplier supplier = suppliers.findById(po.supplierId()).orElseThrow();
        long units = received.stream().mapToLong(q -> q.good() + q.damaged()).sum();
        boolean onTime = due == null || !calendar.today(r.nodeId()).isAfter(due);
        suppliers.update(supplier.recordReceipt(onTime, units, defective, now));

        GoodsReceipt done = receipts.update(r.complete(posted, entry.id(), by, now));
        events.publish(RetailEvents.RECEIPT_POSTED, RetailEvents.AGGREGATE, done.id().toString(),
                RetailEventPayloads.ReceiptPosted.of(done));
        events.publish(RetailEvents.LANDED_COST_ALLOCATED, RetailEvents.AGGREGATE, done.id().toString(),
                RetailEventPayloads.LandedCostAllocated.of(done));
        for (Discrepancy d : done.discrepancies()) {
            events.publish(RetailEvents.DISCREPANCY_RAISED, RetailEvents.AGGREGATE, done.id().toString(),
                    RetailEventPayloads.DiscrepancyEvent.of(done, d));
        }
        events.publish(RetailEvents.PO_RECEIVED, RetailEvents.AGGREGATE, updatedPo.id().toString(),
                RetailEventPayloads.PoEvent.of(updatedPo, by, "Goods received (" + done.number() + ")"));
        return done;
    }

    private ReceiptValidation validation(GoodsReceipt r, Map<UUID, RetailItem> byId) {
        Set<String> existing = lots.existing(lotKeys(r), r.id());
        return validator.validate(r, byId, existing, calendar.today(r.nodeId()));
    }

    private LandedCost cost(GoodsReceipt r, PurchaseOrder po, Map<UUID, RetailItem> byId) {
        List<LandedCostInput> inputs = r.lines().stream().filter(l -> l.received() > 0)
                .map(l -> LandedCostInputs.of(l.lineNo(), po, l.poLineNo(), l.received(), byId)).toList();
        List<LandedCharge> charges = r.shipmentId() == null ? r.charges()
                : shipments.findById(r.shipmentId()).orElseThrow().charges();
        return landedCost.calculate(settings.baseCurrency(), inputs, charges);
    }

    private InventoryReceipt inventoryRequest(GoodsReceipt r, Map<UUID, RetailItem> byId) {
        List<InventoryReceipt.Line> lines = new ArrayList<>();
        for (ReceiptLine l : r.lines()) {
            if (l.received() == 0) {
                continue;
            }
            RetailItem item = byId.get(l.itemId());
            lines.add(new InventoryReceipt.Line(l.sku(), l.lotNo(), l.mfgDate(), l.expDate(), l.qtyGood(),
                    l.qtyDamaged(), l.bin(), r.landedCost().line(l.lineNo()).unitCostMinor(),
                    r.landedCost().currency(), item == null ? null : item.shelfLifeDays(),
                    l.qa() == QaResult.FAIL && l.qtyGood() > 0 ? "QA failed at receipt " + r.number() : null));
        }
        return new InventoryReceipt(r.number(), r.nodeId(), settings.retailOwnerOrg(), r.shipmentRef(), lines);
    }

    private static Optional<InventoryLot> lotOf(InventoryReceiptResult result, ReceiptLine line) {
        return result.lots().stream().filter(l -> l.sku().equals(line.sku()) && l.lotNo().equals(line.lotNo()))
                .findFirst();
    }

    private static List<String> lotKeys(GoodsReceipt r) {
        return r.lines().stream().filter(l -> l.received() > 0 && l.lotNo() != null)
                .map(l -> ReceiptValidator.lotKey(l.sku(), l.lotNo())).distinct().toList();
    }

    private Map<UUID, RetailItem> itemsOf(GoodsReceipt r) {
        return items.findByIds(r.lines().stream().map(ReceiptLine::itemId).distinct().toList()).stream()
                .collect(Collectors.toMap(RetailItem::id, Function.identity()));
    }

    private GoodsReceipt find(UUID id, Long expectedVersion) {
        GoodsReceipt r = receipts.findById(id).orElseThrow(() -> new NotFoundException("retail.receipt-not-found",
                "Goods receipt not found"));
        r.ensureVersion(expectedVersion);
        return r;
    }
}
