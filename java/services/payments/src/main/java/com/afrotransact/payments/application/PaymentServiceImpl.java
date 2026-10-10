package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentSplit;
import com.afrotransact.payments.domain.PaymentSplitter;
import com.afrotransact.payments.domain.PaymentStatus;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.RefundLine;
import com.afrotransact.payments.domain.RefundStatus;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.BalanceTxn;
import com.afrotransact.payments.port.StripeGateway.IntentSnapshot;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

/**
 * Every write runs in {@link MoneyTransactions#serializable}: the payment row is locked, the Stripe call is made with
 * an idempotency key derived from the order ({@code <orderId>:capture:<shipmentId>} …), the journal is posted and the
 * outbox event written in the same transaction. A concurrent duplicate waits on the row lock, fails serialization,
 * is retried and then replays the stored result.
 */
@Service
class PaymentServiceImpl implements PaymentService {

    private static final int MAX_LINES = 200;

    private final PaymentRepository payments;
    private final FeeScheduleRepository feeSchedules;
    private final SettlementRepository settlements;
    private final StripeGateway stripe;
    private final PaymentSplitter splitter;
    private final LedgerPoster ledger;
    private final IdempotencyStore idempotency;
    private final PaymentStatusUpdater statuses;
    private final WalletSync wallet;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final DomainEventPublisher events;
    private final Clock clock;

    PaymentServiceImpl(PaymentRepository payments, FeeScheduleRepository feeSchedules,
                       SettlementRepository settlements, StripeGateway stripe, PaymentSplitter splitter,
                       LedgerPoster ledger, IdempotencyStore idempotency, PaymentStatusUpdater statuses,
                       WalletSync wallet, MoneyTransactions tx, PaymentsAccess access, DomainEventPublisher events,
                       Clock clock) {
        this.payments = payments;
        this.feeSchedules = feeSchedules;
        this.settlements = settlements;
        this.stripe = stripe;
        this.splitter = splitter;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.statuses = statuses;
        this.wallet = wallet;
        this.tx = tx;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public PaymentResult create(CreatePaymentCommand raw, String idempotencyKey) {
        access.service();
        CreatePaymentCommand cmd = validate(raw);
        String fingerprint = Fingerprint.of(cmd);
        return tx.serializable(() -> {
            Optional<StoredRef> prior = idempotency.claim("payment-create", idempotencyKey, fingerprint,
                    StoredRef.class);
            if (prior.isPresent()) {
                return new PaymentResult(requirePayment(prior.get().id()), true);
            }
            Optional<Payment> existing = payments.findByOrderId(cmd.orderId());
            if (existing.isPresent()) {
                return replayCreate(existing.get(), cmd, idempotencyKey);
            }
            Instant now = Instant.now(clock);
            Instant placedAt = cmd.placedAt() == null ? now : cmd.placedAt();
            FeeSchedule schedule = FeeSchedule.activeAt(feeSchedules.all(), placedAt).orElseThrow(() ->
                    new PaymentsStateConflict("payments.no-fee-schedule", "No fee schedule is in force at "
                            + placedAt));
            UUID paymentId = Fingerprint.id("payment", cmd.orderId());
            String stripeCustomer = cmd.saveForOffSession()
                    ? wallet.ensureCustomer(cmd.shopperId()).stripeCustomerId() : cmd.customerId();
            Payment payment = Payment.create(paymentId, cmd.orderId(), stripeCustomer, cmd.amount(), schedule.id(),
                    placedAt, cmd.metadata(), cmd.lines(), now);
            if (!payments.claim(payment)) {
                throw new PaymentsStateConflict("payments.payment-in-flight", "The payment of order "
                        + cmd.orderId() + " is being created; retry");
            }
            Map<String, String> metadata = new LinkedHashMap<>(cmd.metadata());
            metadata.put("order_id", cmd.orderId());
            metadata.put("payment_id", paymentId.toString());
            if (cmd.saveForOffSession()) {
                metadata.put("afrotransact_customer_id", cmd.shopperId());
            }
            IntentSnapshot intent = stripe.createIntent(new StripeGateway.CreateIntent(cmd.amount(),
                    payment.customerId(), cmd.paymentMethodTypes(), "AfroTransact order " + cmd.orderId(), metadata,
                    cmd.orderId(), cmd.saveForOffSession()), cmd.orderId() + ":create-intent");
            Payment stored = payments.update(payment.withIntent(intent.id(), intent.clientSecret(),
                    PaymentStatus.fromStripe(intent.status()), now));
            statuses.publish(PaymentsEvents.INTENT_CREATED, stored);
            idempotency.complete("payment-create", idempotencyKey, new StoredRef(stored.id()));
            return new PaymentResult(stored, false);
        });
    }

    private PaymentResult replayCreate(Payment existing, CreatePaymentCommand cmd, String idempotencyKey) {
        List<OrderLine> lines = existing.lines().stream().map(PaymentLine::line).toList();
        if (!existing.amount().equals(cmd.amount()) || !lines.equals(cmd.lines())) {
            throw new PaymentsStateConflict("payments.order-already-paid", "Order " + cmd.orderId()
                    + " already has a payment with different amount or lines");
        }
        idempotency.complete("payment-create", idempotencyKey, new StoredRef(existing.id()));
        return new PaymentResult(existing, true);
    }

    @Override
    public Payment get(UUID paymentId, boolean refresh) {
        access.reader();
        if (!refresh) {
            return requirePayment(paymentId);
        }
        return tx.serializable(() -> {
            Payment p = payments.lockById(paymentId).orElseThrow(() -> notFound(paymentId.toString()));
            if (p.stripePaymentIntentId() == null || p.status().isFinal()) {
                return p;
            }
            IntentSnapshot intent = stripe.retrieveIntent(p.stripePaymentIntentId());
            if (statuses.awaitingCustomer(p, intent.status())) {
                return p;
            }
            PaymentStatus reported = intent.failureMessage() != null
                    && "requires_payment_method".equals(intent.status()) ? PaymentStatus.FAILED
                    : PaymentStatus.fromStripe(intent.status());
            return statuses.apply(p, reported, intent.failureMessage());
        });
    }

    @Override
    public Payment getByOrder(String orderId) {
        Actor actor = access.current();
        if (actor.type() == PrincipalType.CUSTOMER) {
            // S241: shoppers read only their own orders' payments; staff and services keep their access below.
            Payment p = payments.findByOrderId(orderId).orElseThrow(() -> notFound(orderId));
            if (!ownedBy(p, actor.subject())) {
                throw new ForbiddenActionException("payments.not-your-order",
                        "This order's payment belongs to another customer");
            }
            return p;
        }
        access.reader();
        return payments.findByOrderId(orderId).orElseThrow(() -> notFound(orderId));
    }

    /**
     * The shopper owns the payment when the order's customer id (metadata {@code customer_id} from orders, or
     * {@code afrotransact_customer_id}) is the token subject, or the payment's Stripe Customer is the shopper's.
     */
    private boolean ownedBy(Payment p, String subject) {
        if (subject == null || subject.isBlank()) {
            return false;
        }
        if (subject.equals(p.metadata().get("customer_id")) || subject.equals(p.metadata()
                .get("afrotransact_customer_id")) || subject.equals(p.customerId())) {
            return true;
        }
        return p.customerId() != null && wallet.stripeCustomerOf(subject).map(p.customerId()::equals).orElse(false);
    }

    @Override
    public CaptureResult capture(UUID paymentId, CaptureCommand cmd, String idempotencyKey) {
        Actor actor = access.service();
        if (cmd.shipmentId() == null || cmd.shipmentId().isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "shipmentId is required",
                    Map.of("shipmentId", "Required"));
        }
        String fingerprint = Fingerprint.of(paymentId + "|" + cmd);
        String scope = "capture";
        return tx.serializable(() -> {
            Optional<StoredRef> prior = idempotency.claim(scope, idempotencyKey, fingerprint, StoredRef.class);
            if (prior.isPresent()) {
                Capture done = payments.findCapture(prior.get().id()).orElseThrow();
                return new CaptureResult(done, requirePayment(done.paymentId()), true);
            }
            Payment p = payments.lockById(paymentId).orElseThrow(() -> notFound(paymentId.toString()));
            Optional<Capture> already = payments.findCapture(p.id(), cmd.shipmentId());
            if (already.isPresent()) {
                idempotency.complete(scope, idempotencyKey, new StoredRef(already.get().id()));
                return new CaptureResult(already.get(), p, true);
            }
            if (!p.status().isCapturable() && p.stripePaymentIntentId() != null && !p.status().isFinal()) {
                IntentSnapshot intent = stripe.retrieveIntent(p.stripePaymentIntentId());
                p = statuses.apply(p, PaymentStatus.fromStripe(intent.status()), intent.failureMessage());
            }
            List<OrderLine> lines = linesToCapture(p, cmd.lineIds());
            Money amount = lines.stream().map(OrderLine::total).reduce(Money.zero(p.currency()), Money::plus);
            if (cmd.amount() != null && !cmd.amount().equals(amount)) {
                throw new PaymentsRuleViolation("payments.capture-amount-mismatch", "The shipment's lines add up to "
                        + amount + ", not " + cmd.amount(), Map.of("amountMinor", "Must equal the lines' total"));
            }
            boolean finalCapture = cmd.finalCapture() || p.captured().plus(amount).equals(p.amount());
            UUID captureId = Fingerprint.id("capture", p.id().toString(), cmd.shipmentId());
            Payment capturable = p;
            if (!capturable.status().isCapturable()) {
                throw new PaymentsStateConflict("payments.not-capturable", "Payment of order " + p.orderId() + " is "
                        + p.status() + "; only AUTHORIZED or PARTIALLY_CAPTURED payments can be captured");
            }
            IntentSnapshot captured = stripe.capture(p.stripePaymentIntentId(), amount, finalCapture,
                    Map.of("order_id", p.orderId(), "shipment_id", cmd.shipmentId()),
                    p.stripeKeyPrefix() + ":capture:" + cmd.shipmentId());
            FeeSchedule schedule = schedule(p);
            Fee fee = captureFee(p, captured.latestChargeId(), amount, schedule);
            PaymentSplit split = splitter.split(lines, schedule, fee.amount());
            Instant now = Instant.now(clock);
            UUID journalId = Fingerprint.id("journal", "capture", captureId.toString());
            Journal journal = Postings.capture(journalId, now, "capture:" + captureId, p.orderId(),
                    p.id().toString(), captured.latestChargeId(), split);
            ledger.post(journal, actor.subject());
            Capture capture = new Capture(captureId, p.id(), cmd.shipmentId(), amount, fee.amount(), fee.estimated(),
                    captured.latestChargeId(), finalCapture, lines.stream().map(OrderLine::lineId).toList(),
                    journalId, now);
            payments.insertCapture(capture);
            Payment stored = payments.update(capturable.capture(captureId, amount, split.lines(), finalCapture, now));
            settlements.reopen(p.orderId(), now);
            events.publish(PaymentsEvents.CAPTURED, PaymentsEvents.AGGREGATE_PAYMENT, p.orderId(),
                    PaymentsEventPayloads.CapturedPayload.of(stored, capture, split));
            idempotency.complete(scope, idempotencyKey, new StoredRef(captureId));
            return new CaptureResult(capture, stored, false);
        });
    }

    private List<OrderLine> linesToCapture(Payment p, List<String> lineIds) {
        List<PaymentLine> chosen = new ArrayList<>();
        if (lineIds.isEmpty()) {
            p.lines().stream().filter(l -> !l.isCaptured()).forEach(chosen::add);
        } else {
            for (String id : new LinkedHashSet<>(lineIds)) {
                PaymentLine pl = p.line(id).orElseThrow(() -> new PaymentsRuleViolation("payments.unknown-line",
                        "Order " + p.orderId() + " has no line " + id, Map.of("lineIds", "Unknown line " + id)));
                if (pl.isCaptured()) {
                    throw new PaymentsStateConflict("payments.line-already-captured", "Line " + id
                            + " was already captured by another shipment");
                }
                chosen.add(pl);
            }
        }
        if (chosen.isEmpty()) {
            throw new PaymentsStateConflict("payments.nothing-to-capture", "Every line of order " + p.orderId()
                    + " is already captured");
        }
        return chosen.stream().map(PaymentLine::line).toList();
    }

    /** Stripe's real fee for this capture from the charge's balance transactions, else the schedule's estimate. */
    private Fee captureFee(Payment p, String chargeId, Money amount, FeeSchedule schedule) {
        if (chargeId != null) {
            List<BalanceTxn> txns = stripe.chargeBalanceTransactions(chargeId);
            if (!txns.isEmpty()) {
                long total = txns.stream().mapToLong(BalanceTxn::fee).sum();
                long booked = payments.captures(p.id()).stream()
                        .filter(c -> chargeId.equals(c.stripeChargeId()) && !c.feeEstimated())
                        .mapToLong(c -> c.stripeFee().minor()).sum();
                if (total - booked >= 0) {
                    return new Fee(Money.of(total - booked, p.currency()), false);
                }
            }
        }
        return new Fee(schedule.estimateCardFee(amount), true);
    }

    @Override
    public Payment cancel(UUID paymentId, String reason, String idempotencyKey) {
        access.service();
        return tx.serializable(() -> {
            Payment p = payments.lockById(paymentId).orElseThrow(() -> notFound(paymentId.toString()));
            if (p.status() == PaymentStatus.CANCELED) {
                return p;
            }
            Payment canceled = p.cancel(Instant.now(clock));
            if (p.stripePaymentIntentId() != null) {
                stripe.cancel(p.stripePaymentIntentId(), p.stripeKeyPrefix() + ":cancel");
            }
            Payment stored = payments.update(canceled);
            statuses.publish(PaymentsEvents.INTENT_CANCELED, stored);
            return stored;
        });
    }

    @Override
    public ReleaseResult release(UUID paymentId, String reason, String idempotencyKey) {
        access.service();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required",
                    "Releasing an authorisation needs an Idempotency-Key");
        }
        String fingerprint = Fingerprint.of(paymentId + "|release");
        String scope = "release";
        return tx.serializable(() -> {
            Optional<StoredRef> prior = idempotency.claim(scope, idempotencyKey, fingerprint, StoredRef.class);
            if (prior.isPresent()) {
                return new ReleaseResult(requirePayment(prior.get().id()), true);
            }
            Payment p = payments.lockById(paymentId).orElseThrow(() -> notFound(paymentId.toString()));
            if (p.status().isFinal()) {
                idempotency.complete(scope, idempotencyKey, new StoredRef(p.id()));
                return new ReleaseResult(p, true);
            }
            Instant now = Instant.now(clock);
            Payment released = p.releaseRemaining(now);
            if (p.stripePaymentIntentId() != null) {
                IntentSnapshot intent = stripe.retrieveIntent(p.stripePaymentIntentId());
                if (p.captured().isZero()) {
                    if (!"canceled".equals(intent.status())) {
                        stripe.cancel(p.stripePaymentIntentId(), p.stripeKeyPrefix() + ":cancel");
                    }
                } else if ("requires_capture".equals(intent.status()) && intent.amountCapturable() > 0) {
                    // Multicapture intent with captures: cancelling releases the uncaptured rest. A card without
                    // multicapture already released it at the first (partial) capture.
                    stripe.releaseUncaptured(p.stripePaymentIntentId(), p.stripeKeyPrefix() + ":release");
                }
            }
            // An authorisation never reaches the ledger (1000 Stripe clearing moves only at capture), so releasing
            // its rest posts no journal: a memo journal would not match any Stripe balance transaction.
            Payment stored = payments.update(released);
            if (stored.status() == PaymentStatus.CANCELED) {
                statuses.publish(PaymentsEvents.INTENT_CANCELED, stored);
            }
            events.publish(PaymentsEvents.AUTHORIZATION_RELEASED, PaymentsEvents.AGGREGATE_PAYMENT, stored.orderId(),
                    PaymentsEventPayloads.ReleasedPayload.of(stored, reason));
            idempotency.complete(scope, idempotencyKey, new StoredRef(stored.id()));
            return new ReleaseResult(stored, false);
        });
    }

    @Override
    public List<Payment> offSessionCharges(UUID paymentId) {
        access.reader();
        requirePayment(paymentId);
        return payments.children(paymentId);
    }

    @Override
    public RefundResult refund(UUID paymentId, RefundCommand cmd, String idempotencyKey) {
        Actor actor = access.service();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required", "Refunds need an Idempotency-Key");
        }
        if (cmd.lines().isEmpty()) {
            throw new PaymentsRuleViolation("payments.invalid-refund", "A refund needs at least one line",
                    Map.of("lines", "Required"));
        }
        String fingerprint = Fingerprint.of(paymentId + "|" + cmd);
        return tx.serializable(() -> {
            Optional<StoredRef> prior = idempotency.claim("refund", idempotencyKey, fingerprint, StoredRef.class);
            if (prior.isPresent()) {
                Refund done = payments.findRefund(prior.get().id()).orElseThrow();
                return new RefundResult(done, requirePayment(done.paymentId()), List.of(), true);
            }
            Payment p = payments.lockById(paymentId).orElseThrow(() -> notFound(paymentId.toString()));
            FeeSchedule schedule = schedule(p);
            UUID refundId = Fingerprint.id("refund", p.id().toString(), idempotencyKey);
            List<RefundLine> lines = new ArrayList<>();
            Payment working = p;
            for (RefundCommand.Line l : cmd.lines()) {
                PaymentLine pl = working.line(l.lineId()).orElseThrow(() -> new PaymentsRuleViolation(
                        "payments.unknown-line", "Order " + p.orderId() + " has no line " + l.lineId(),
                        Map.of("lines", "Unknown line " + l.lineId())));
                Money zero = Money.zero(p.currency());
                RefundLine rl = RefundLine.allocate(pl, Objects.requireNonNullElse(l.items(), zero),
                        Objects.requireNonNullElse(l.shipping(), zero), Objects.requireNonNullElse(l.tax(), zero),
                        schedule.reverseCommissionOnRefund());
                lines.add(rl);
                working = working.refund(new Refund(refundId, p.id(), p.orderId(), null, rl.total(),
                        RefundStatus.PENDING, null, List.of(rl), null, null), Instant.now(clock));
            }
            Money amount = lines.stream().map(RefundLine::total).reduce(Money.zero(p.currency()), Money::plus);
            if (!amount.isPositive()) {
                throw new PaymentsRuleViolation("payments.invalid-refund", "The refund amount must be positive");
            }
            Instant now = Instant.now(clock);
            StripeGateway.RefundSnapshot stripeRefund = stripe.refund(p.stripePaymentIntentId(), amount,
                    Map.of("order_id", p.orderId(), "refund_id", refundId.toString()),
                    p.stripeKeyPrefix() + ":refund:" + refundId);
            Refund refund = new Refund(refundId, p.id(), p.orderId(), stripeRefund.id(), amount,
                    RefundStatus.PENDING, cmd.reason(), lines, now, null);
            ledger.post(Postings.refund(Fingerprint.id("journal", "refund", refundId.toString()), now,
                    "refund:" + refundId, p.orderId(), p.id().toString(), stripeRefund.id(), lines),
                    actor.subject());
            payments.insertRefund(refund);
            if ("succeeded".equals(stripeRefund.status())) {
                refund = settleRefund(refund, now, actor.subject());
            }
            Payment stored = payments.update(p.refund(refund, now));
            List<RefundResult.Reversal> reversals = reverseTransfers(p, refund, now, actor.subject());
            events.publish(PaymentsEvents.REFUNDED, PaymentsEvents.AGGREGATE_PAYMENT, p.orderId(),
                    PaymentsEventPayloads.RefundedPayload.of(refund, reversals));
            idempotency.complete("refund", idempotencyKey, new StoredRef(refundId));
            return new RefundResult(refund, stored, reversals, false);
        });
    }

    /** Stripe paid the refund out of the platform balance: Dr 2200 / Cr 1000. */
    private Refund settleRefund(Refund refund, Instant now, String by) {
        ledger.post(Postings.refundSettled(Fingerprint.id("journal", "refund-settled", refund.id().toString()), now,
                "refund-settled:" + refund.id(), refund), by);
        Refund settled = refund.settled(now);
        payments.updateRefund(settled);
        return settled;
    }

    /**
     * When a seller's net for the order was already transferred, take back what the refund debits from them
     * (capped at what is left of their transfers) with Stripe transfer reversals.
     */
    private List<RefundResult.Reversal> reverseTransfers(Payment p, Refund refund, Instant now, String by) {
        Map<String, Money> debitByOrg = new LinkedHashMap<>();
        for (RefundLine l : refund.lines()) {
            debitByOrg.merge(l.sellerOrg(), l.sellerDebit(), Money::plus);
        }
        List<RefundResult.Reversal> out = new ArrayList<>();
        for (Map.Entry<String, Money> e : debitByOrg.entrySet()) {
            Money toReverse = e.getValue();
            for (SettlementTransfer t : settlements.lockTransfers(p.orderId(), e.getKey())) {
                if (!toReverse.isPositive()) {
                    break;
                }
                Money amount = toReverse.min(t.remaining());
                if (!amount.isPositive()) {
                    continue;
                }
                StripeGateway.ReversalSnapshot rev = stripe.reverseTransfer(t.stripeTransferId(), amount,
                        Map.of("order_id", p.orderId(), "refund_id", refund.id().toString()),
                        p.stripeKeyPrefix() + ":reversal:" + refund.id() + ":" + t.id());
                settlements.insertReversal(t.id(), rev.id(), amount, refund.id(), now);
                settlements.updateTransfer(t.reversedBy(amount));
                ledger.post(Postings.transferReversal(Fingerprint.id("journal", "reversal", rev.id()), now,
                        "reversal:" + rev.id(), p.orderId(), e.getKey(), amount, rev.id()), by);
                out.add(new RefundResult.Reversal(e.getKey(), t.stripeTransferId(), rev.id(), amount.minor()));
                toReverse = toReverse.minus(amount);
            }
        }
        return out;
    }

    @Override
    public List<Capture> captures(UUID paymentId) {
        access.reader();
        return payments.captures(paymentId);
    }

    @Override
    public List<Refund> refunds(UUID paymentId) {
        access.reader();
        return payments.refunds(paymentId);
    }

    private FeeSchedule schedule(Payment p) {
        return feeSchedules.findById(p.feeScheduleId()).orElseThrow(() -> new IllegalStateException(
                "Fee schedule " + p.feeScheduleId() + " of order " + p.orderId() + " is missing"));
    }

    private Payment requirePayment(UUID id) {
        return payments.findById(id).orElseThrow(() -> notFound(id.toString()));
    }

    private static CreatePaymentCommand validate(CreatePaymentCommand cmd) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (cmd.orderId() == null || cmd.orderId().isBlank()) {
            errors.put("orderId", "Required");
        }
        if (cmd.amount() == null) {
            errors.put("amountMinor", "Required");
        }
        if (cmd.lines().isEmpty() || cmd.lines().size() > MAX_LINES) {
            errors.put("lines", "1 to " + MAX_LINES + " lines");
        }
        Set<String> ids = new HashSet<>();
        for (OrderLine l : cmd.lines()) {
            if (!ids.add(l.lineId())) {
                errors.put("lines", "Duplicate line id " + l.lineId());
            }
            if (cmd.amount() != null && !l.currency().equals(cmd.amount().currency())) {
                errors.put("lines", "Every line must be in " + cmd.amount().currency());
            }
        }
        if (cmd.saveForOffSession() && (cmd.shopperId() == null || cmd.shopperId().isBlank())) {
            errors.put("shopperId", "Required to save the payment method for off-session charges");
        }
        if (!errors.isEmpty()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Invalid payment request", errors);
        }
        return new CreatePaymentCommand(cmd.orderId().trim(), cmd.customerId(), cmd.amount(),
                cmd.paymentMethodTypes(), cmd.placedAt(), cmd.metadata(), cmd.lines(),
                cmd.shopperId() == null ? null : cmd.shopperId().trim(), cmd.saveForOffSession());
    }

    private static NotFoundException notFound(String what) {
        return new NotFoundException("payments.payment-not-found", "No payment " + what);
    }

    /** Stripe fee of a capture. */
    private record Fee(Money amount, boolean estimated) {
    }
}
