package com.afrotransact.taxreturns.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.domain.returns.DeliveredLine;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.InspectionOutcome;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.domain.returns.LineEligibility;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.RefundBreakdown;
import com.afrotransact.taxreturns.domain.returns.RefundSelection;
import com.afrotransact.taxreturns.domain.returns.RefundState;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnLineRequest;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;
import com.afrotransact.taxreturns.domain.returns.ReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaLine;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;
import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.StockStatus;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import com.afrotransact.taxreturns.port.RmaRepository;
import com.afrotransact.taxreturns.port.SellerPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class ReturnServiceImpl implements ReturnService {

    static final String CARRIER_CONSUMER = "taxreturns.carrier-tracking";
    private static final Logger LOG = LoggerFactory.getLogger(ReturnServiceImpl.class);
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int MAX_NOTE = 1000;

    private final OrderSnapshotRepository orders;
    private final RmaRepository rmas;
    private final SellerPolicyRepository sellers;
    private final ReturnPolicy policy;
    private final RefundOrchestrator refunds;
    private final ReturnWorkflow workflow;
    private final RmaEvents rmaEvents;
    private final InboxDeduplicator inbox;
    private final Access access;
    private final TransactionOperations tx;
    private final Clock clock;
    private final OrderIntakeService intake;
    private final SecureRandom random = new SecureRandom();

    ReturnServiceImpl(OrderSnapshotRepository orders, RmaRepository rmas, SellerPolicyRepository sellers,
                      ReturnPolicy policy, RefundOrchestrator refunds, ReturnWorkflow workflow, RmaEvents rmaEvents,
                      InboxDeduplicator inbox, Access access, TransactionOperations tx, Clock clock,
                      OrderIntakeService intake) {
        this.intake = intake;
        this.orders = orders;
        this.rmas = rmas;
        this.sellers = sellers;
        this.policy = policy;
        this.refunds = refunds;
        this.workflow = workflow;
        this.rmaEvents = rmaEvents;
        this.inbox = inbox;
        this.access = access;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public ReturnEligibility eligibility(UUID orderId) {
        PlacedOrder order = order(orderId);
        access.customerOrStaff(order.customerId());
        Map<String, Integer> open = rmas.openQuantities(orderId);
        Instant now = Instant.now(clock);
        List<ReturnEligibility.Shipment> shipments = new ArrayList<>();
        for (PlacedOrder.Shipment s : order.shipments()) {
            Optional<SellerReturnPolicy> seller = sellerPolicy(s);
            List<ReturnEligibility.Line> lines = new ArrayList<>();
            for (PlacedOrder.Line l : s.lines()) {
                LineEligibility e = policy.eligibility(delivered(s, l, open), s.fulfilment(), s.delivery(), seller,
                        now);
                lines.add(new ReturnEligibility.Line(l.lineId(), l.atin(), l.title(), l.quantity(),
                        l.unitPriceMinor(), e));
            }
            shipments.add(new ReturnEligibility.Shipment(s.shipmentId(), s.sellerOrgId(), s.sellerName(),
                    s.fulfilment(), s.delivery(), s.deliveredAt(), lines));
        }
        return new ReturnEligibility(order.orderId(), order.orderNumber(), order.currency(), shipments);
    }

    @Override
    public Rma request(RequestReturnCommand c, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw new ReturnRuleViolation("returns.idempotency-key-required", "Send an Idempotency-Key header");
        }
        PlacedOrder order = order(c.orderId());
        Actor actor = access.customerOrStaff(order.customerId());
        Optional<Rma> replay = rmas.findByIdempotencyKey(order.customerId(), idempotencyKey);
        if (replay.isPresent()) {
            if (!replay.get().shipmentId().equals(c.shipmentId())) {
                throw new ConflictException("returns.idempotency-key-reused",
                        "This Idempotency-Key was used for another return");
            }
            return replay.get();
        }
        PlacedOrder.Shipment shipment = order.shipments().stream()
                .filter(s -> s.shipmentId().equals(c.shipmentId())).findFirst()
                .orElseThrow(() -> new NotFoundException("returns.shipment-not-found",
                        "Shipment not found on order " + order.orderNumber()));
        Rma created = tx.execute(s -> create(order, shipment, c, idempotencyKey, actor));
        if (created.status().approved()) {
            return workflow.afterApproval(created.id());
        }
        return created;
    }

    private Rma create(PlacedOrder order, PlacedOrder.Shipment shipment, RequestReturnCommand c, String key,
                       Actor actor) {
        Map<String, Integer> open = rmas.openQuantities(order.orderId());
        Optional<SellerReturnPolicy> seller = sellerPolicy(shipment);
        Instant now = Instant.now(clock);
        Map<String, LineEligibility> eligibility = new LinkedHashMap<>();
        Map<String, DeliveredLine> delivered = new LinkedHashMap<>();
        for (PlacedOrder.Line l : shipment.lines()) {
            DeliveredLine d = delivered(shipment, l, open);
            delivered.put(l.lineId(), d);
            eligibility.put(l.lineId(), policy.eligibility(d, shipment.fulfilment(), shipment.delivery(), seller, now));
        }
        policy.validate(c.method(), c.lines(), eligibility, delivered);
        // The parcel leaves from the order's delivery address (orders.order.placed / orders lookup); an address the
        // customer gives (staff on their behalf, e.g. they moved) still wins.
        ReturnAddress from = c.returnFrom() != null && c.returnFrom().complete() ? c.returnFrom()
                : c.method() == ReturnMethod.NO_RETURN ? c.returnFrom() : intake.customerAddress(order).orElse(null);
        if ((c.method() == ReturnMethod.DROP_OFF || c.method() == ReturnMethod.DRIVER_PICKUP)
                && (from == null || !from.complete())) {
            throw new ReturnRuleViolation("returns.address-required", "The order's delivery address is not known; "
                    + "give the address the parcel ships from",
                    Map.of("returnFrom", "Street, city and ZIP are required for a label or a pickup"));
        }
        List<RmaLine> lines = new ArrayList<>();
        List<RefundSelection> selections = new ArrayList<>();
        for (ReturnLineRequest r : c.lines()) {
            DeliveredLine d = delivered.get(r.lineId());
            lines.add(new RmaLine(r.lineId(), d.atin(), d.title(), r.quantity(), d.unitPriceMinor(), r.reason(),
                    r.comment(), r.evidenceAssetIds(), null, null, StockStatus.NOT_TRACKED, null));
            selections.add(new RefundSelection(r.lineId(), r.quantity(), r.reason().sellerFault()));
        }
        int feeBps = policy.restockingFeeBps(shipment.fulfilment(), seller);
        long estimate = refunds.estimate(order, selections, feeBps).totalMinor();
        boolean auto = policy.autoApprove(shipment.fulfilment(), c.method(), estimate);
        Instant due = !auto && !shipment.fulfilment().afroTransactFulfilled() ? policy.approvalDueAt(now) : null;
        Rma rma = rmas.insert(Rma.requested(UUID.randomUUID(), number(), order.orderId(), order.orderNumber(),
                shipment.shipmentId(), order.customerId(), shipment.sellerOrgId(), shipment.fulfilment(),
                shipment.delivery(), c.method(), lines, order.currency(), estimate, due,
                policy.refundAtFirstScan(shipment.fulfilment(), c.method(), estimate, seller), from, now),
                key);
        String who = Access.describe(actor);
        rmas.recordTransition(rma.id(), null, RmaStatus.REQUESTED, who, null, now);
        rmaEvents.publish(TaxReturnsEvents.RMA_REQUESTED, rma, who);
        if (auto) {
            rma = rmaEvents.save(rma, rma.approve(true, null, now), TaxReturnsEvents.RMA_APPROVED,
                    ReturnWorkflow.SYSTEM, "Auto-approved");
        }
        return rma;
    }

    @Override
    public Rma get(UUID id) {
        Rma rma = workflow.load(id);
        access.viewer(rma.customerId(), rma.sellerOrgId());
        return rma;
    }

    @Override
    public Rma getByNumber(String number) {
        Rma rma = rmas.findByNumber(number.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("returns.rma-not-found", "Return not found"));
        access.viewer(rma.customerId(), rma.sellerOrgId());
        return rma;
    }

    @Override
    public List<Rma> search(ReturnSearch q) {
        Actor a = access.current();
        int limit = q.limit() <= 0 || q.limit() > 200 ? 50 : q.limit();
        if (a.type() == PrincipalType.CUSTOMER) {
            return rmas.search(new RmaRepository.Filter(a.subject(), null, q.orderId(), q.status(), limit));
        }
        if (access.isStaff(a)) {
            access.staff(TaxReturnsPermissions.RETURNS_MANAGE);
            return rmas.search(new RmaRepository.Filter(null, q.sellerOrgId(), q.orderId(), q.status(), limit));
        }
        String org = a.orgId().orElseThrow(() -> new ForbiddenActionException("returns.no-organisation",
                "Act for an organisation to see its returns"));
        access.orgOrStaff(TaxReturnsPermissions.RETURNS_MANAGE, org);
        return rmas.search(new RmaRepository.Filter(null, org, q.orderId(), q.status(), limit));
    }

    @Override
    public Rma cancel(UUID id) {
        Rma cancelled = tx.execute(s -> {
            Rma before = workflow.load(id);
            Actor a = access.customerOrStaff(before.customerId());
            if (!before.status().cancellable()) {
                throw new ConflictException("returns.cancel-window-closed",
                        "The parcel is already on its way; this return can't be cancelled");
            }
            return rmaEvents.save(before, before.cancel(Instant.now(clock)), TaxReturnsEvents.RMA_CANCELLED,
                    Access.describe(a), "Cancelled before drop-off");
        });
        if (cancelled.method() == ReturnMethod.DRIVER_PICKUP) {
            workflow.cancelPickup(cancelled.id(), "Return " + cancelled.number() + " cancelled");
        }
        return cancelled;
    }

    @Override
    public Rma approve(UUID id, String note) {
        Rma approved = tx.execute(s -> {
            Rma before = workflow.load(id);
            Actor a = decider(before);
            return rmaEvents.save(before, before.approve(false, note(note), Instant.now(clock)),
                    TaxReturnsEvents.RMA_APPROVED, Access.describe(a), note);
        });
        return workflow.afterApproval(approved.id());
    }

    @Override
    public Rma reject(UUID id, String note) {
        if (note == null || note.isBlank()) {
            throw new ReturnRuleViolation("returns.note-required", "Tell the customer why",
                    Map.of("note", "Required when rejecting"));
        }
        return tx.execute(s -> {
            Rma before = workflow.load(id);
            Actor a = decider(before);
            return rmaEvents.save(before, before.reject(note(note), Instant.now(clock)),
                    TaxReturnsEvents.RMA_REJECTED, Access.describe(a), note);
        });
    }

    @Override
    public Rma issueLabel(UUID id) {
        Rma rma = workflow.load(id);
        access.staff(TaxReturnsPermissions.RETURNS_MANAGE);
        if (!rma.status().approved()) {
            throw new ConflictException("returns.invalid-transition", "Return " + rma.number() + " is "
                    + rma.status().name().toLowerCase(Locale.ROOT) + "; only approved returns get a label");
        }
        return workflow.afterApproval(id);
    }

    @Override
    public Rma pickedUp(UUID id, String handoverCode) {
        Rma moved = tx.execute(s -> {
            Rma before = workflow.load(id);
            Actor a = access.current();
            if (a.type() == PrincipalType.DRIVER) {
                if (!a.can(TaxReturnsPermissions.DELIVERY_OPERATE)) {
                    throw new ForbiddenActionException("auth.missing-permission",
                            "Missing permission " + TaxReturnsPermissions.DELIVERY_OPERATE);
                }
                if (before.handoverCode() == null || !before.handoverCode().equals(handoverCode)) {
                    throw new ReturnRuleViolation("returns.wrong-handover-code", "The handover code doesn't match",
                            Map.of("handoverCode", "Ask the customer for the 6-digit code"));
                }
            } else {
                a = access.staff(TaxReturnsPermissions.RETURNS_MANAGE);
            }
            if (before.method() != ReturnMethod.DRIVER_PICKUP) {
                throw new ConflictException("returns.not-driver-pickup", "This return is dropped off at a carrier");
            }
            return rmaEvents.save(before, before.inTransit(Instant.now(clock)), TaxReturnsEvents.RMA_IN_TRANSIT,
                    Access.describe(a), "Collected by driver");
        });
        return firstScanRefund(moved);
    }

    @Override
    public Rma receive(UUID id) {
        Rma received = tx.execute(s -> {
            Rma before = workflow.load(id);
            Actor a = handler(before);
            return rmaEvents.save(before, before.received(Instant.now(clock)), TaxReturnsEvents.RMA_RECEIVED,
                    Access.describe(a), null);
        });
        return firstScanRefund(received);
    }

    @Override
    public Rma inspect(UUID id, InspectCommand c) {
        Rma inspected = tx.execute(s -> {
            Rma before = workflow.load(id);
            Actor a = handler(before);
            Map<String, InspectCommand.Line> byLine = new LinkedHashMap<>();
            Map<String, String> errors = new LinkedHashMap<>();
            for (int i = 0; i < c.lines().size(); i++) {
                InspectCommand.Line l = c.lines().get(i);
                if (l.lineId() == null || before.lines().stream().noneMatch(r -> r.lineId().equals(l.lineId()))) {
                    errors.put("lines[" + i + "].lineId", "Not a line of this return");
                } else if (l.outcome() == null) {
                    errors.put("lines[" + i + "].outcome", "PASS or FAIL");
                } else {
                    byLine.put(l.lineId(), l);
                }
            }
            for (RmaLine r : before.lines()) {
                if (!byLine.containsKey(r.lineId()) && errors.keySet().stream().noneMatch(k -> k.startsWith("lines"))) {
                    errors.put("lines", "Inspect every line (" + r.lineId() + " missing)");
                }
            }
            if (!errors.isEmpty()) {
                throw new ReturnRuleViolation("returns.invalid-inspection", "The inspection is incomplete", errors);
            }
            StockStatus stock = before.fulfilment().afroTransactFulfilled() ? StockStatus.PENDING
                    : StockStatus.NOT_TRACKED;
            List<RmaLine> lines = before.lines().stream().map(r -> {
                InspectCommand.Line l = byLine.get(r.lineId());
                ItemCondition cond = l.condition() != null ? l.condition()
                        : l.outcome() == InspectionOutcome.PASS ? ItemCondition.RESTOCK : ItemCondition.DAMAGED;
                return r.inspected(l.outcome(), cond, stock, note(l.note()));
            }).toList();
            return rmaEvents.save(before, before.inspected(lines, note(c.note()), Instant.now(clock)),
                    TaxReturnsEvents.RMA_INSPECTED, Access.describe(a), c.note());
        });
        if (inspected.fulfilment().afroTransactFulfilled()) {
            workflow.postStock(inspected.id());
        }
        Rma current = workflow.load(inspected.id());
        boolean anyPass = current.lines().stream().anyMatch(l -> l.outcome() == InspectionOutcome.PASS);
        if (anyPass && current.refundState() != RefundState.ISSUED) {
            return workflow.refund(current.id());
        }
        return tx.execute(s -> {
            Rma before = workflow.load(id);
            if (before.status() != RmaStatus.INSPECTED) {
                return before;
            }
            Instant now = Instant.now(clock);
            Rma after = anyPass ? before.refunded(now)
                    : before.reject("Inspection failed" + (c.note() == null ? "" : ": " + note(c.note())), now);
            return rmaEvents.save(before, after, anyPass ? TaxReturnsEvents.RMA_REFUNDED
                    : TaxReturnsEvents.RMA_REJECTED, ReturnWorkflow.SYSTEM, null);
        });
    }

    @Override
    public RefundBreakdown refundQuote(UUID id) {
        Rma rma = get(id);
        PlacedOrder order = order(rma.orderId());
        PlacedOrder.Shipment shipment = order.shipments().stream()
                .filter(s -> s.shipmentId().equals(rma.shipmentId())).findFirst().orElseThrow();
        if (rma.refundState() == RefundState.ISSUED) {
            return new RefundBreakdown(List.of());
        }
        return refunds.estimate(order, RefundOrchestrator.selections(rma),
                policy.restockingFeeBps(rma.fulfilment(), sellerPolicy(shipment)));
    }

    @Override
    public boolean onCarrierScan(UUID eventId, CarrierScan scan) {
        String state = scan.state() == null ? "" : scan.state().toLowerCase(Locale.ROOT);
        boolean transit = state.equals("picked_up") || state.equals("in_transit") || state.equals("out_for_delivery");
        boolean delivered = state.equals("delivered");
        Optional<Rma> found = scan.carrier() == null || scan.trackingNumber() == null ? Optional.empty()
                : rmas.findByTracking(scan.carrier().toLowerCase(Locale.ROOT), scan.trackingNumber());
        if (found.isEmpty() && scan.reference() != null && scan.reference().startsWith("RMA-")) {
            found = rmas.findByNumber(scan.reference());
        }
        if (found.isEmpty() || !(transit || delivered)) {
            return false;
        }
        UUID rmaId = found.get().id();
        Rma moved = tx.execute(s -> {
            if (!inbox.firstDelivery(CARRIER_CONSUMER, eventId)) {
                return null;
            }
            Rma before = workflow.load(rmaId);
            Instant at = scan.occurredAt() == null ? Instant.now(clock) : scan.occurredAt();
            String who = "CARRIER:" + scan.carrier();
            if (transit && before.status() == RmaStatus.LABEL_ISSUED) {
                return rmaEvents.save(before, before.inTransit(at), TaxReturnsEvents.RMA_IN_TRANSIT, who, state);
            }
            if (delivered && (before.status() == RmaStatus.LABEL_ISSUED || before.status() == RmaStatus.IN_TRANSIT)) {
                return rmaEvents.save(before, before.received(at), TaxReturnsEvents.RMA_RECEIVED, who, state);
            }
            LOG.debug("Return {}: carrier {} ignored in status {}", before.number(), state, before.status());
            return null;
        });
        if (moved == null) {
            return false;
        }
        firstScanRefund(moved);
        return true;
    }

    /** Refund at first scan: once the parcel is with the carrier or driver (or received without a scan). */
    private Rma firstScanRefund(Rma rma) {
        if (rma.refundAtFirstScan() && rma.refundState() == RefundState.NONE
                && (rma.status() == RmaStatus.IN_TRANSIT || rma.status() == RmaStatus.RECEIVED)) {
            return workflow.refund(rma.id());
        }
        return rma;
    }

    /** Sellers decide on their own seller-fulfilled returns; staff on everything. */
    private Actor decider(Rma rma) {
        if (rma.fulfilment() == FulfilmentType.SELLER_FULFILLED) {
            return access.orgOrStaff(TaxReturnsPermissions.RETURNS_MANAGE, rma.sellerOrgId());
        }
        return access.staff(TaxReturnsPermissions.RETURNS_MANAGE);
    }

    /** Who receives and inspects: the seller for seller-fulfilled returns, AfroTransact staff for the rest. */
    private Actor handler(Rma rma) {
        return decider(rma);
    }

    private PlacedOrder order(UUID orderId) {
        return intake.order(orderId).orElseThrow(() -> new NotFoundException("returns.order-not-found",
                "Order not found (or not placed yet)"));
    }

    private Optional<SellerReturnPolicy> sellerPolicy(PlacedOrder.Shipment s) {
        return s.fulfilment() == FulfilmentType.SELLER_FULFILLED ? sellers.find(s.sellerOrgId()) : Optional.empty();
    }

    private static DeliveredLine delivered(PlacedOrder.Shipment s, PlacedOrder.Line l, Map<String, Integer> open) {
        return new DeliveredLine(l.lineId(), s.shipmentId().toString(), l.atin(), l.title(), l.quantity(),
                l.unitPriceMinor(), l.taxMinor(), l.productType(), l.perishable(), s.deliveredAt(),
                open.getOrDefault(l.lineId(), 0));
    }

    private String number() {
        StringBuilder sb = new StringBuilder("RMA-");
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String note(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note.trim();
    }
}
