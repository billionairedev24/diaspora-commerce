package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.Refund;
import com.afrotransact.taxreturns.domain.returns.RefundBreakdown;
import com.afrotransact.taxreturns.domain.returns.RefundCalculator;
import com.afrotransact.taxreturns.domain.returns.RefundKind;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.domain.returns.RefundSelection;
import com.afrotransact.taxreturns.domain.returns.RefundState;
import com.afrotransact.taxreturns.domain.returns.RefundStatus;
import com.afrotransact.taxreturns.domain.returns.RefundableLine;
import com.afrotransact.taxreturns.domain.returns.RefundedTotals;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;
import com.afrotransact.taxreturns.domain.returns.ReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaLine;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import com.afrotransact.taxreturns.port.PaymentsGateway;
import com.afrotransact.taxreturns.port.RefundRepository;
import com.afrotransact.taxreturns.port.RmaRepository;
import com.afrotransact.taxreturns.port.SellerPolicyRepository;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Refund orchestration (S115): compute from the lines as charged by payments-service, record the refund as PENDING,
 * call {@code POST /v1/payments/{id}/refunds} with {@code Idempotency-Key = refund id}, then in one transaction mark it
 * issued, reverse the tax, close the RMA and publish events. Retryable failures stay PENDING for the job; refusals
 * fail the refund.
 */
@Component
class RefundOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(RefundOrchestrator.class);

    private final PaymentsGateway payments;
    private final RefundRepository refunds;
    private final RmaRepository rmas;
    private final OrderSnapshotRepository orders;
    private final SellerPolicyRepository sellers;
    private final RefundCalculator calculator;
    private final ReturnPolicy policy;
    private final TaxLedger ledger;
    private final RmaEvents rmaEvents;
    private final TransactionOperations tx;
    private final Clock clock;

    RefundOrchestrator(PaymentsGateway payments, RefundRepository refunds, RmaRepository rmas,
                       OrderSnapshotRepository orders, SellerPolicyRepository sellers, RefundCalculator calculator,
                       ReturnPolicy policy, TaxLedger ledger, RmaEvents rmaEvents, TransactionOperations tx,
                       Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.rmas = rmas;
        this.orders = orders;
        this.sellers = sellers;
        this.calculator = calculator;
        this.policy = policy;
        this.ledger = ledger;
        this.rmaEvents = rmaEvents;
        this.tx = tx;
        this.clock = clock;
    }

    /** Estimate shown before payments is asked: from the order snapshot (shipping derived per shipment). */
    RefundBreakdown estimate(PlacedOrder order, List<RefundSelection> selections, int restockingFeeBps) {
        Map<String, RefundedTotals> done = refunds.refundedTotals(order.orderId());
        List<RefundableLine> charged = new ArrayList<>();
        for (PlacedOrder.Shipment s : order.shipments()) {
            boolean first = true;
            for (PlacedOrder.Line l : s.lines()) {
                RefundedTotals t = done.getOrDefault(l.lineId(), RefundedTotals.NONE);
                charged.add(new RefundableLine(l.lineId(), s.shipmentId().toString(), l.quantity(),
                        l.unitPriceMinor() * l.quantity(), first ? s.shippingMinor() : 0L, l.taxMinor(), t.quantity(),
                        t.itemsMinor(), t.shippingMinor(), t.taxMinor()));
                first = false;
            }
        }
        return calculator.forReturn(charged, selections, restockingFeeBps);
    }

    /** Selections of the RMA lines that are refundable (all before inspection; passing ones after). */
    static List<RefundSelection> selections(Rma rma) {
        return rma.refundableLines().stream()
                .map(l -> new RefundSelection(l.lineId(), l.quantity(), l.reason().sellerFault())).toList();
    }

    /** Computes and records the RMA's refund (idempotent per RMA). Empty when no line is refundable. */
    Optional<Refund> prepareForRma(Rma rma) {
        String key = "rma:" + rma.id();
        Optional<Refund> existing = refunds.findByRequestKey(key);
        if (existing.isPresent()) {
            return existing;
        }
        List<RefundSelection> selections = selections(rma);
        if (selections.isEmpty()) {
            return Optional.empty();
        }
        PaymentsGateway.ChargedPayment payment = payment(rma.orderId());
        int feeBps = policy.restockingFeeBps(rma.fulfilment(), rma.fulfilment() == FulfilmentType.SELLER_FULFILLED
                ? sellers.find(rma.sellerOrgId()) : Optional.empty());
        RefundBreakdown breakdown = calculator.forReturn(charged(rma.orderId(), payment), selections, feeBps);
        requireCaptured(payment, breakdown);
        Refund refund = new Refund(UUID.randomUUID(), RefundKind.RETURN, rma.id(), rma.orderId(),
                payment.paymentId(), payment.currency(), RefundStatus.PENDING, breakdown.lines(),
                "Return " + rma.number() + reasons(rma), key, null, null, 0, Instant.now(clock), null, 0L);
        return Optional.of(tx.execute(s -> refunds.insertOrGet(refund)));
    }

    /** Computes and records a partial (adjustment) refund. */
    Refund prepareAdjustment(UUID orderId, List<AdjustmentLine> lines, String reason, String requestKey) {
        Optional<Refund> existing = refunds.findByRequestKey(requestKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        PaymentsGateway.ChargedPayment payment = payment(orderId);
        Map<String, RefundableLine> charged = new HashMap<>();
        charged(orderId, payment).forEach(l -> charged.put(l.lineId(), l));
        List<RefundLineAmount> amounts = new ArrayList<>();
        for (AdjustmentLine a : lines) {
            RefundableLine l = charged.get(a.lineId());
            if (l == null) {
                throw new ReturnRuleViolation("returns.unknown-line", "Line " + a.lineId() + " was not charged");
            }
            amounts.add(calculator.adjustment(l, a.itemsMinor(), a.shippingMinor()));
        }
        RefundBreakdown breakdown = new RefundBreakdown(amounts);
        requireCaptured(payment, breakdown);
        Refund refund = new Refund(UUID.randomUUID(), RefundKind.ADJUSTMENT, null, orderId, payment.paymentId(),
                payment.currency(), RefundStatus.PENDING, amounts, reason, requestKey, null, null, 0,
                Instant.now(clock), null, 0L);
        return tx.execute(s -> refunds.insertOrGet(refund));
    }

    /** Sends a pending refund to payments and completes it. */
    Refund execute(UUID refundId) {
        Refund r = refunds.findById(refundId)
                .orElseThrow(() -> new NotFoundException("returns.refund-not-found", "Refund not found"));
        if (r.status() != RefundStatus.PENDING) {
            return r;
        }
        List<RefundLineAmount> lines = r.lines().stream().filter(l -> l.totalMinor() > 0L).toList();
        try {
            PaymentsGateway.IssuedRefund issued = payments.refund(r.paymentId(), lines, r.reason(),
                    r.id().toString());
            return tx.execute(s -> complete(r, issued));
        } catch (UpstreamException ex) {
            if (ex.retryable()) {
                LOG.warn("Refund {} not issued yet (payments {}): {}; will retry", r.id(), ex.status(),
                        ex.getMessage());
                return tx.execute(s -> refunds.update(r.retryLater(ex.getMessage())));
            }
            LOG.warn("Refund {} refused by payments ({} {}): {}", r.id(), ex.status(), ex.code(), ex.getMessage());
            return tx.execute(s -> fail(r, ex.getMessage()));
        }
    }

    private Refund complete(Refund pending, PaymentsGateway.IssuedRefund issued) {
        Instant now = Instant.now(clock);
        Refund current = refunds.findById(pending.id()).orElseThrow();
        if (current.status() != RefundStatus.PENDING) {
            return current;
        }
        String ref = issued.refundId() == null || issued.refundId().isBlank() ? pending.id().toString()
                : issued.refundId();
        Refund done = refunds.update(current.issued(ref, now));
        ledger.recordRefund(done.orderId(), ref, done.currency(), done.lines().stream()
                .map(l -> new ReversalLine(l.lineId(), l.itemsMinor() + l.restockingFeeMinor(), l.taxMinor()))
                .toList(), now);
        String number = null;
        if (done.rmaId() != null) {
            Rma before = rmas.findById(done.rmaId()).orElseThrow();
            number = before.number();
            Rma after = before.withRefundState(RefundState.ISSUED, now);
            boolean close = before.status() == RmaStatus.INSPECTED
                    || before.status().approved() && before.method() == ReturnMethod.NO_RETURN;
            if (close) {
                after = after.refunded(now);
            }
            rmaEvents.save(before, after, close ? TaxReturnsEvents.RMA_REFUNDED : null, "SYSTEM:refunds",
                    "Refund " + done.id() + " issued");
        }
        rmaEvents.refund(TaxReturnsEvents.REFUND_ISSUED, done, number, now);
        return done;
    }

    private Refund fail(Refund pending, String message) {
        Instant now = Instant.now(clock);
        Refund current = refunds.findById(pending.id()).orElseThrow();
        Refund failed = refunds.update(current.failed(message));
        String number = null;
        if (failed.rmaId() != null) {
            Rma before = rmas.findById(failed.rmaId()).orElseThrow();
            number = before.number();
            rmaEvents.save(before, before.withRefundState(RefundState.FAILED, now), null, "SYSTEM:refunds", message);
        }
        rmaEvents.refund(TaxReturnsEvents.REFUND_FAILED, failed, number, now);
        return failed;
    }

    private PaymentsGateway.ChargedPayment payment(UUID orderId) {
        return payments.paymentForOrder(orderId).orElseThrow(() -> new ReturnRuleViolation(
                "returns.payment-not-found", "No payment found for order " + orderId));
    }

    private List<RefundableLine> charged(UUID orderId, PaymentsGateway.ChargedPayment payment) {
        Map<String, String> shipmentOf = new HashMap<>();
        orders.find(orderId).ifPresent(o -> o.shipments().forEach(s -> s.lines()
                .forEach(l -> shipmentOf.put(l.lineId(), s.shipmentId().toString()))));
        Map<String, RefundedTotals> done = refunds.refundedTotals(orderId);
        return payment.lines().stream().map(l -> {
            RefundedTotals t = done.getOrDefault(l.lineId(), RefundedTotals.NONE);
            return new RefundableLine(l.lineId(), shipmentOf.getOrDefault(l.lineId(), l.lineId()), l.quantity(),
                    l.itemsMinor(), l.shippingMinor(), l.taxMinor(), t.quantity(), t.itemsMinor(), t.shippingMinor(),
                    t.taxMinor());
        }).toList();
    }

    private static void requireCaptured(PaymentsGateway.ChargedPayment payment, RefundBreakdown breakdown) {
        for (RefundLineAmount a : breakdown.lines()) {
            boolean captured = payment.lines().stream()
                    .anyMatch(l -> l.lineId().equals(a.lineId()) && l.captured());
            if (!captured && a.totalMinor() > 0L) {
                throw new ReturnRuleViolation("returns.not-captured", "Line " + a.lineId()
                        + " has not been captured yet; cancel the shipment instead");
            }
        }
    }

    private static String reasons(Rma rma) {
        List<String> r = rma.refundableLines().stream().map(RmaLine::reason).map(Enum::name).distinct().toList();
        return r.isEmpty() ? "" : " (" + String.join(", ", r) + ")";
    }
}
