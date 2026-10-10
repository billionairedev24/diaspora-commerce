package com.afrotransact.retail.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.retail.domain.AsnLine;
import com.afrotransact.retail.domain.Carton;
import com.afrotransact.retail.domain.DocumentStatus;
import com.afrotransact.retail.domain.GlPostingRules;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.LandedCost;
import com.afrotransact.retail.domain.LandedCostCalculator;
import com.afrotransact.retail.domain.LandedCostInput;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.ShipmentStage;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.port.ItemRepository;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.PurchaseOrderRepository;
import com.afrotransact.retail.port.ReferenceIssuer;
import com.afrotransact.retail.port.ShipmentRepository;
import com.afrotransact.retail.port.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShipmentServiceImpl implements ShipmentService {

    private final ShipmentRepository shipments;
    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final ItemRepository items;
    private final ReferenceIssuer refs;
    private final LandedCostCalculator landedCost;
    private final GlPostingRules gl;
    private final JournalPoster journal;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final RetailSettings settings;
    private final Clock clock;

    ShipmentServiceImpl(ShipmentRepository shipments, PurchaseOrderRepository orders, SupplierRepository suppliers,
                        ItemRepository items, ReferenceIssuer refs, LandedCostCalculator landedCost,
                        GlPostingRules gl, JournalPoster journal, PermissionChecker permissions,
                        DomainEventPublisher events, RetailSettings settings, Clock clock) {
        this.shipments = shipments;
        this.orders = orders;
        this.suppliers = suppliers;
        this.items = items;
        this.refs = refs;
        this.landedCost = landedCost;
        this.gl = gl;
        this.journal = journal;
        this.permissions = permissions;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InboundShipment book(BookShipmentCommand c) {
        Actor actor = permissions.require(RetailPermissions.LOGISTICS);
        PurchaseOrder po = orders.findById(c.poId()).orElseThrow(() -> new RetailRuleViolation(
                "retail.po-not-found", "Purchase order " + c.poId() + " not found"));
        if (c.mode() == null) {
            throw new RetailRuleViolation("retail.shipment-invalid", "Transport mode is required");
        }
        Supplier supplier = suppliers.findById(po.supplierId()).orElseThrow();
        Instant now = Instant.now(clock);
        List<AsnLineInput> input = c.lines() == null || c.lines().isEmpty()
                ? po.lines().stream().filter(l -> l.openQty() > 0)
                        .map(l -> new AsnLineInput(l.lineNo(), l.openQty(), null, null, null)).toList()
                : c.lines();
        String ref = refs.nextShipmentReference(supplier.country(), po.orderDate());
        PurchaseOrder booked = orders.update(po.bookShipment(ref, actor.subject(), now));
        InboundShipment shipment = shipments.insert(InboundShipment.book(UUID.randomUUID(), ref, booked, c.mode(),
                c.carrier(), c.trackingRef(), c.billOfLading(), c.originPort(), c.destinationPort(), c.etd(), c.eta(),
                asnLines(booked, input), c.charges(), actor.subject(), now));
        events.publish(RetailEvents.PO_UPDATED, RetailEvents.AGGREGATE, booked.id().toString(),
                RetailEventPayloads.PoEvent.of(booked, actor.subject(), "Shipment " + ref + " booked"));
        publish(RetailEvents.SHIPMENT_BOOKED, shipment);
        return shipment;
    }

    @Override
    @Transactional
    public InboundShipment updateAsn(UUID id, List<AsnLineInput> lines, List<Carton> cartons, Long expectedVersion) {
        permissions.require(RetailPermissions.LOGISTICS);
        InboundShipment s = find(id, expectedVersion);
        PurchaseOrder po = orders.findById(s.poId()).orElseThrow();
        InboundShipment updated = shipments.update(s.updateAsn(po, asnLines(po, lines), cartons, Instant.now(clock)));
        publish(RetailEvents.SHIPMENT_UPDATED, updated);
        return updated;
    }

    @Override
    @Transactional
    public InboundShipment setDocument(UUID id, String name, DocumentStatus status, String reference, String note) {
        permissions.require(RetailPermissions.LOGISTICS);
        if (status == null) {
            throw new RetailRuleViolation("retail.document-invalid", "Document status is required");
        }
        InboundShipment s = find(id, null);
        InboundShipment updated = shipments.update(s.setDocument(name, status, reference, note, Instant.now(clock)));
        publish(RetailEvents.SHIPMENT_UPDATED, updated);
        return updated;
    }

    @Override
    @Transactional
    public InboundShipment updateCharges(UUID id, List<LandedCharge> charges, Long expectedVersion) {
        permissions.require(RetailPermissions.LOGISTICS);
        InboundShipment s = find(id, expectedVersion);
        InboundShipment updated = shipments.update(s.updateCharges(charges, Instant.now(clock)));
        publish(RetailEvents.SHIPMENT_UPDATED, updated);
        return updated;
    }

    @Override
    @Transactional
    public InboundShipment advance(UUID id, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.LOGISTICS);
        InboundShipment s = find(id, expectedVersion);
        InboundShipment moved = shipments.update(s.advance(actor.subject(), Instant.now(clock)));
        if (moved.stage() == ShipmentStage.DEPARTED) {
            long fob = estimate(moved).fobMinor();
            journal.post(JournalSource.SHIPMENT_DEPARTED, moved.reference(), "Title transferred at departure ("
                    + moved.poNumber() + ") — goods in transit", gl.titleTransfer(fob), actor.subject());
        }
        publish(RetailEvents.SHIPMENT_STAGE_CHANGED, moved);
        return moved;
    }

    @Override
    @Transactional(readOnly = true)
    public LandedCost landedCostEstimate(UUID id) {
        permissions.require(RetailPermissions.READ);
        return estimate(find(id, null));
    }

    @Override
    @Transactional(readOnly = true)
    public InboundShipment get(UUID id) {
        permissions.require(RetailPermissions.READ);
        return find(id, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InboundShipment> list(ShipmentStage stage, UUID poId, int page, int size) {
        permissions.require(RetailPermissions.READ);
        return shipments.page(stage, poId, Math.max(0, page), Math.clamp(size, 1, 200));
    }

    private LandedCost estimate(InboundShipment s) {
        PurchaseOrder po = orders.findById(s.poId()).orElseThrow();
        Map<UUID, RetailItem> byId = items.findByIds(po.lines().stream().map(PoLine::itemId).toList()).stream()
                .collect(Collectors.toMap(RetailItem::id, Function.identity()));
        List<LandedCostInput> inputs = s.lines().stream()
                .map(l -> LandedCostInputs.of(l.lineNo(), po, l.poLineNo(), l.qty(), byId)).toList();
        return landedCost.calculate(settings.baseCurrency(), inputs, s.charges());
    }

    private static List<AsnLine> asnLines(PurchaseOrder po, List<AsnLineInput> input) {
        if (input == null) {
            return List.of();
        }
        List<AsnLine> out = new ArrayList<>();
        int n = 1;
        for (AsnLineInput in : input) {
            PoLine pl = po.line(in.poLineNo());
            out.add(new AsnLine(n++, pl.lineNo(), pl.itemId(), pl.sku(), in.qty(), in.lotNo(), in.mfgDate(),
                    in.expDate()));
        }
        return out;
    }

    private InboundShipment find(UUID id, Long expectedVersion) {
        InboundShipment s = shipments.findById(id).orElseThrow(() -> new NotFoundException(
                "retail.shipment-not-found", "Shipment not found"));
        s.ensureVersion(expectedVersion);
        return s;
    }

    private void publish(String type, InboundShipment s) {
        events.publish(type, RetailEvents.AGGREGATE, s.id().toString(), RetailEventPayloads.ShipmentEvent.of(s));
    }
}
