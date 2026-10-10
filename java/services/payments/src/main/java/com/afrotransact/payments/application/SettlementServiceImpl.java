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
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.domain.SettlementKind;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.domain.ShipmentSettlement;
import com.afrotransact.payments.port.ConnectedAccountRepository;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.PaymentsMetrics;
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
    private final Clock clock;

    SettlementServiceImpl(SettlementRepository settlements, PaymentRepository payments, LedgerRepository ledgerReader,
                          ConnectedAccountRepository accounts, StripeGateway stripe, LedgerPoster ledger,
                          MoneyTransactions tx, PaymentsAccess access, DomainEventPublisher events,
                          PaymentsMetrics metrics, PaymentsSettings settings, Clock clock) {
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
        this.clock = clock;
    }

    @Override
    public Settlement markDelivered(String orderId, Instant deliveredAt, SettlementKind kind) {
        access.service();
        return tx.serializable(() -> {
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

    /** Transfers every positive seller payable of the order; leaves it OPEN for a later check if one is blocked. */
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
            if (e.getValue() <= 0) {
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
            if (amount <= 0) {
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

    /** What happened to one transfer attempt. */
    private enum Outcome {
        CREATED,
        BLOCKED
    }

    private Outcome transfer(String orderId, String org, Money amount, Instant now) {
        Optional<String> account = accounts.find(org);
        if (account.isEmpty()) {
            LOG.warn("Order {}: {} has no connected account; its net stays on hold", orderId, org);
            metrics.transfer("no_account");
            return Outcome.BLOCKED;
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
}
