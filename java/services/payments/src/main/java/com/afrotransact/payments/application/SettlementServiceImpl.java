package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.domain.SettlementKind;
import com.afrotransact.payments.domain.SettlementTransfer;
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
    public int releaseMatured(int maxOrders) {
        int transfers = 0;
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
            String org = e.getKey();
            Optional<String> account = accounts.find(org);
            if (account.isEmpty()) {
                LOG.warn("Order {}: {} has no connected account; its net stays on hold", s.orderId(), org);
                metrics.transfer("no_account");
                blocked = true;
                continue;
            }
            int seq = settlements.transfers(s.orderId(), org).size() + 1;
            Money amount = Money.of(e.getValue(), currency);
            StripeGateway.TransferSnapshot transfer;
            try {
                transfer = stripe.createTransfer(amount, account.get(), s.orderId(),
                        Map.of("order_id", s.orderId(), "org_id", org),
                        s.orderId() + ":transfer:" + org + ":" + seq);
            } catch (StripeGatewayException ex) {
                LOG.warn("Order {}: transfer to {} failed ({}); will retry", s.orderId(), org, ex.getMessage());
                metrics.transfer("failed");
                blocked = true;
                continue;
            }
            UUID id = Fingerprint.id("transfer", s.orderId(), org, Integer.toString(seq));
            SettlementTransfer t = new SettlementTransfer(id, s.orderId(), org, transfer.id(), amount,
                    Money.zero(currency), now);
            settlements.insertTransfer(t);
            ledger.post(Postings.transfer(Fingerprint.id("journal", "transfer", transfer.id()), now,
                    "transfer:" + transfer.id(), s.orderId(), org, amount, transfer.id()), SYSTEM);
            events.publish(PaymentsEvents.TRANSFER_CREATED, PaymentsEvents.AGGREGATE_PAYMENT, s.orderId(),
                    PaymentsEventPayloads.TransferPayload.of(t));
            metrics.transfer("created");
            created++;
        }
        settlements.markChecked(s.orderId(), !blocked, blocked ? now.plus(settings.retryRecheck()) : null, now);
        return created;
    }
}
