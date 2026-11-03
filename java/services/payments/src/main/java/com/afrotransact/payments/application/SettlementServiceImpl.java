package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.Chart;
import com.afrotransact.payments.domain.EntrySide;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.JournalLine;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PlanFeeWithholding;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.domain.SettlementKind;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.domain.ShipmentSettlement;
import com.afrotransact.payments.port.ConnectedAccountRepository;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.PaymentsMetrics;
import com.afrotransact.payments.port.PlanFeeWithholdingRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGatewayException;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class SettlementServiceImpl implements SettlementService {

    private static final Logger LOG = LoggerFactory.getLogger(SettlementServiceImpl.class);
    private static final String SYSTEM = "system:settlement";

    private final SettlementRepository settlements;
    private final PaymentRepository payments;
    private final LedgerRepository ledgerReader;
    private final ConnectedAccountRepository accounts;
    private final StripeGateway stripe;
    private final LedgerPoster ledger;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final DomainEventPublisher events;
    private final PaymentsMetrics metrics;
    private final PaymentsSettings settings;
    private final PlanFeeWithholdingRepository planFees;
    private final TransferRecoveryLedger recoveries;
    private final MembershipBenefitsService membership;
    private final Clock clock;

    SettlementServiceImpl(SettlementRepository settlements, PaymentRepository payments, LedgerRepository ledgerReader,
                          ConnectedAccountRepository accounts, StripeGateway stripe, LedgerPoster ledger,
                          MoneyTransactions tx, PaymentsAccess access, DomainEventPublisher events,
                          PaymentsMetrics metrics, PaymentsSettings settings, PlanFeeWithholdingRepository planFees,
                          TransferRecoveryLedger recoveries, MembershipBenefitsService membership, Clock clock) {
        this.settlements = settlements;
        this.payments = payments;
        this.ledgerReader = ledgerReader;
        this.accounts = accounts;
        this.stripe = stripe;
        this.ledger = ledger;
        this.tx = tx;
        this.access = access;
        this.events = events;
        this.metrics = metrics;
        this.settings = settings;
        this.planFees = planFees;
        this.recoveries = recoveries;
        this.membership = membership;
        this.clock = clock;
    }

    @Override
    public Settlement markDelivered(String orderId, Instant deliveredAt, SettlementKind kind) {
        access.service();
        Settlement settlement = tx.serializable(() -> {
            payments.findByOrderId(orderId).orElseThrow(() -> new NotFoundException("payments.payment-not-found",
                    "No payment for order " + orderId));
            Instant now = Instant.now(clock);
            Settlement s = Settlement.delivered(orderId, kind == null ? SettlementKind.PRODUCT : kind,
                    deliveredAt == null ? now : deliveredAt, settings.settlementHold(), now);
            if (!settlements.insert(s)) {
                return settlements.find(orderId).orElseThrow();
            }
            return s;
        });
        // a member's cashback on the delivered order (S323): once per order, after the settlement committed
        membership.onOrderDelivered(orderId);
        return settlement;
    }

    @Override
    public Settlement get(String orderId) {
        access.reader();
        return settlements.find(orderId).orElseThrow(() -> new NotFoundException("payments.settlement-not-found",
                "Order " + orderId + " is not delivered yet"));
    }

    @Override
    public ShipmentSettlement markShipmentDelivered(String orderId, String shipmentId, Instant deliveredAt,
                                                    SettlementKind kind) {
        access.service();
        return tx.serializable(() -> {
            payments.findByOrderId(orderId).orElseThrow(() -> new NotFoundException("payments.payment-not-found",
                    "No payment for order " + orderId));
            Instant now = Instant.now(clock);
            ShipmentSettlement s = ShipmentSettlement.delivered(orderId, shipmentId,
                    kind == null ? SettlementKind.PRODUCT : kind, deliveredAt == null ? now : deliveredAt,
                    settings.settlementHold(), now);
            if (!settlements.insertShipment(s)) {
                return settlements.findShipment(orderId, shipmentId).orElseThrow();
            }
            return s;
        });
    }

    @Override
    public List<ShipmentSettlement> shipments(String orderId) {
        access.reader();
        return settlements.shipments(orderId);
    }

    @Override
    public int releaseMatured(int maxOrders) {
        int transfers = 0;
        for (int i = 0; i < maxOrders; i++) {
            Integer created = tx.serializable(() -> {
                Instant now = Instant.now(clock);
                Optional<ShipmentSettlement> next = settlements.lockNextMaturedShipment(now);
                return next.map(s -> settleShipment(s, now)).orElse(null);
            });
            if (created == null) {
                break;
            }
            transfers += created;
        }
        for (int i = 0; i < maxOrders; i++) {
            Integer created = tx.serializable(() -> {
                Instant now = Instant.now(clock);
                Optional<Settlement> next = settlements.lockNextMatured(now);
                return next.map(s -> settle(s, now)).orElse(null);
            });
            if (created == null) {
                break;
            }
            transfers += created;
        }
        return transfers;
    }

    @Override
    public List<SettlementTransfer> transfers(String orgId, String orderId, int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        if (orgId != null && !orgId.isBlank()) {
            String org = access.orgOf(orgId);
            access.balanceViewer(org);
            return orderId == null || orderId.isBlank() ? settlements.transfersOf(org, n)
                    : settlements.transfers(orderId.trim(), org);
        }
        access.reader();
        return settlements.transfersOfOrder(orderId == null ? "" : orderId.trim());
    }

    /**
     * Transfers every positive seller payable of the order; leaves it OPEN for a later check if one is blocked.
     * AfroTransact Retail is never transferred to (its sales are 4400 revenue, not a 2000 payable).
     */
    private int settle(Settlement s, Instant now) {
        Optional<Payment> payment = payments.findByOrderId(s.orderId());
        if (payment.isEmpty()) {
            settlements.markChecked(s.orderId(), true, null, now);
            return 0;
        }
        String currency = payment.get().currency();
        boolean blocked = false;
        int created = 0;
        for (Map.Entry<String, Long> e : ledgerReader.payableByOrg(s.orderId(), currency).entrySet()) {
            if (e.getValue() <= 0 || isRetail(e.getKey())) {
                continue;
            }
            Outcome outcome = transfer(s.orderId(), e.getKey(), Money.of(e.getValue(), currency), now);
            blocked |= outcome == Outcome.BLOCKED;
            created += outcome == Outcome.CREATED ? 1 : 0;
        }
        settlements.markChecked(s.orderId(), !blocked, blocked ? now.plus(settings.retryRecheck()) : null, now);
        return created;
    }

    /**
     * Transfers the seller net captured for one shipment (its capture journal's 2000 credits per organisation),
     * capped at what the organisation's payable on the order still holds (refunds and earlier transfers are already
     * deducted there), so per-shipment and per-order transfers together never exceed the payable.
     */
    private int settleShipment(ShipmentSettlement s, Instant now) {
        Optional<Payment> payment = payments.findByOrderId(s.orderId());
        if (payment.isEmpty()) {
            settlements.markShipmentChecked(s.orderId(), s.shipmentId(), true, null, now);
            return 0;
        }
        if (!payment.get().multicapture() && payment.get().capturedPerShipment()) {
            // Single-capture order (no Stripe multicapture): the first shipment's capture took every line, so the
            // nets of shipments not delivered yet are in it too. Sellers are paid by the order's settlement instead,
            // once every shipment is delivered (or cancelled and refunded).
            LOG.info("Order {}: single-capture payment; shipment {} is settled with the order", s.orderId(),
                    s.shipmentId());
            settlements.markShipmentChecked(s.orderId(), s.shipmentId(), true, null, now);
            return 0;
        }
        String currency = payment.get().currency();
        List<Payment> all = new ArrayList<>();
        all.add(payment.get());
        all.addAll(payments.children(payment.get().id()));
        List<Capture> captures = all.stream().flatMap(p -> payments.captures(p.id()).stream())
                .filter(c -> s.shipmentId().equals(c.shipmentId())).toList();
        if (captures.isEmpty()) {
            LOG.info("Order {}: shipment {} delivered but not captured yet; checking again later", s.orderId(),
                    s.shipmentId());
            settlements.markShipmentChecked(s.orderId(), s.shipmentId(), false, now.plus(settings.retryRecheck()),
                    now);
            return 0;
        }
        Map<String, Long> captured = capturedNetByOrg(s.orderId(), captures);
        Map<String, Long> payable = ledgerReader.payableByOrg(s.orderId(), currency);
        boolean blocked = false;
        int created = 0;
        for (Map.Entry<String, Long> e : captured.entrySet()) {
            long amount = Math.min(e.getValue(), payable.getOrDefault(e.getKey(), 0L));
            if (amount <= 0 || isRetail(e.getKey())) {
                continue;
            }
            Outcome outcome = transfer(s.orderId(), e.getKey(), Money.of(amount, currency), now);
            blocked |= outcome == Outcome.BLOCKED;
            created += outcome == Outcome.CREATED ? 1 : 0;
        }
        settlements.markShipmentChecked(s.orderId(), s.shipmentId(), !blocked,
                blocked ? now.plus(settings.retryRecheck()) : null, now);
        return created;
    }

    private Map<String, Long> capturedNetByOrg(String orderId, List<Capture> captures) {
        List<UUID> journalIds = captures.stream().map(Capture::journalId).toList();
        Map<String, Long> out = new LinkedHashMap<>();
        for (Journal j : ledgerReader.journalsOfOrder(orderId)) {
            if (!journalIds.contains(j.id())) {
                continue;
            }
            for (JournalLine l : j.lines()) {
                if (l.account().chart() == Chart.SELLER_PAYABLE) {
                    long signed = l.side() == EntrySide.CREDIT ? l.amount().minor() : -l.amount().minor();
                    out.merge(l.account().key(), signed, Long::sum);
                }
            }
        }
        return out;
    }

    private boolean isRetail(String org) {
        return settings.retailOrgId().equals(org);
    }

    /** What happened to one transfer attempt. */
    private enum Outcome {
        CREATED,
        BLOCKED,
        /** Nothing left to transfer once the organisation's plan fees and label charges were recovered. */
        RECOVERED
    }

    private Outcome transfer(String orderId, String org, Money payable, Instant now) {
        Optional<String> account = accounts.find(org);
        if (account.isEmpty()) {
            LOG.warn("Order {}: {} has no connected account; its net stays on hold", orderId, org);
            metrics.transfer("no_account");
            return Outcome.BLOCKED;
        }
        Money amount = settleOrgCharges(orderId, org, payable, now);
        if (!amount.isPositive()) {
            LOG.info("Order {}: {}'s payable went to its outstanding plan fees and label charges; nothing to "
                    + "transfer", orderId, org);
            metrics.transfer("label_recovered");
            return Outcome.RECOVERED;
        }
        int seq = settlements.transfers(orderId, org).size() + 1;
        StripeGateway.TransferSnapshot transfer;
        try {
            transfer = stripe.createTransfer(amount, account.get(), orderId, Map.of("order_id", orderId, "org_id", org),
                    orderId + ":transfer:" + org + ":" + seq);
        } catch (StripeGatewayException ex) {
            LOG.warn("Order {}: transfer to {} failed ({}); will retry", orderId, org, ex.getMessage());
            metrics.transfer("failed");
            return Outcome.BLOCKED;
        }
        UUID id = Fingerprint.id("transfer", orderId, org, Integer.toString(seq));
        SettlementTransfer t = new SettlementTransfer(id, orderId, org, transfer.id(), amount,
                Money.zero(amount.currency()), now);
        settlements.insertTransfer(t);
        ledger.post(Postings.transfer(Fingerprint.id("journal", "transfer", transfer.id()), now,
                "transfer:" + transfer.id(), orderId, org, amount, transfer.id()), SYSTEM);
        events.publish(PaymentsEvents.TRANSFER_CREATED, PaymentsEvents.AGGREGATE_PAYMENT, orderId,
                PaymentsEventPayloads.TransferPayload.of(t));
        metrics.transfer("created");
        return Outcome.CREATED;
    }

    /**
     * Plan fees withheld from earnings (S298) and label charges (S256) are the organisation's, not an order's: they sit
     * on 2000:org without an order. Before an order's payable is transferred they are settled against it: released plan
     * fees are paid back with it first (the transfer is larger), then refunds Stripe would not take back from earlier
     * transfers are recovered, then withheld plan fees are recovered from it, oldest first (the transfer is smaller, one "Plan fee (invoice …)" deduction each), then outstanding label charges are
     * recovered and credits from voided labels paid. Pure ledger reclasses between the order's and the organisation's
     * unattributed payable; returns what to transfer.
     */
    private Money settleOrgCharges(String orderId, String org, Money payable, Instant now) {
        String currency = payable.currency();
        long unattributed = ledgerReader.unattributedPayable(org, currency);
        long owedBack = recoveries.outstanding(org, currency);
        if (unattributed == 0 && planFees.outstanding(org, currency) == 0 && owedBack == 0) {
            return payable;
        }
        List<PlanFeeWithholding> open = planFees.lockOutstanding(org, currency);
        long planOutstanding = open.stream().mapToLong(w -> w.outstanding().minor()).sum();
        // What is left on the unattributed payable once plan fees and refused reversals are taken out: label charges
        // (negative) or credits from voided labels (positive).
        long labels = unattributed + planOutstanding + owedBack;
        Money remaining = payable;
        for (PlanFeeWithholding w : open) {
            if (w.outstanding().isNegative()) {
                remaining = remaining.plus(settlePlanFee(orderId, w, w.outstanding(), now));
            }
        }
        // Refunds Stripe would not take back from earlier transfers come before everything else: that money already
        // left the platform.
        remaining = remaining.minus(recoveries.recoverFrom(orderId, org, remaining, now));
        for (PlanFeeWithholding w : open) {
            if (w.outstanding().isPositive() && remaining.isPositive()) {
                remaining = remaining.minus(settlePlanFee(orderId, w, w.outstanding().min(remaining), now));
            }
        }
        return settleLabels(orderId, org, remaining, labels, now);
    }

    /** Posts one plan fee settlement (signed) against the order and records the deduction; returns its amount. */
    private Money settlePlanFee(String orderId, PlanFeeWithholding w, Money amount, Instant now) {
        UUID id = UUID.randomUUID();
        UUID journal = Fingerprint.id("journal", "plan-fee-settled", id.toString());
        ledger.post(Postings.planFeeSettled(journal, now, "plan-fee-settled:" + id, orderId, w, amount), SYSTEM);
        planFees.insertDeduction(id, w.id(), orderId, amount, journal, now);
        planFees.update(w.settledBy(amount, now));
        LOG.info("Order {}: {} of plan fee {} settled with {}'s transfer", orderId, amount, w.invoiceId(),
                w.orgId());
        return amount.abs();
    }

    /**
     * Label charges (S256): outstanding charges ({@code labels} negative) are recovered from the payable (the transfer
     * is smaller) and credits from labels voided after their charge was recovered ({@code labels} positive) are paid
     * with it (the transfer is larger).
     */
    private Money settleLabels(String orderId, String org, Money payable, long labels, Instant now) {
        if (labels == 0) {
            return payable;
        }
        long recovered = labels < 0 ? Math.min(Math.max(0, payable.minor()), -labels) : -labels;
        if (recovered == 0) {
            return payable;
        }
        Money adjustment = Money.of(recovered, payable.currency());
        UUID id = UUID.randomUUID();
        ledger.post(Postings.labelRecovery(id, now, "label-recovery:" + id, orderId, org, adjustment), SYSTEM);
        LOG.info("Order {}: {} of {}'s label balance settled against its payable", orderId, adjustment, org);
        return payable.minus(adjustment);
    }
}
