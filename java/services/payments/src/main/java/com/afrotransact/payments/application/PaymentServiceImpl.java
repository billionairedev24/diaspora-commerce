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
import com.afrotransact.payments.domain.CapturePlan;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentMethodType;
import com.afrotransact.payments.domain.PaymentSplit;
import com.afrotransact.payments.domain.PaymentSplitter;
import com.afrotransact.payments.domain.PaymentStatus;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.RefundLine;
import com.afrotransact.payments.domain.RefundStatus;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.BalanceTxn;
import com.afrotransact.payments.port.StripeGateway.IntentSnapshot;
import com.afrotransact.payments.port.StripeGateway.OffSessionAttempt;
import com.afrotransact.payments.port.WalletRepository;
import com.afrotransact.payments.port.WalletRepository.StripeCustomer;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Every write runs in {@link MoneyTransactions#serializable}: the payment row is locked, the Stripe call is made with
 * an idempotency key derived from the order ({@code <orderId>:capture:<shipmentId>} …), the journal is posted and the
 * outbox event written in the same transaction. A concurrent duplicate waits on the row lock, fails serialization,
 * is retried and then replays the stored result.
 */
@Service
class PaymentServiceImpl implements PaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private static final int MAX_LINES = 200;
    /** Stripe cannot charge less than 0.50: credit leaves at least this on the card, or pays everything. */
    private static final long MIN_CARD_MINOR = 50;

    private final PaymentRepository payments;
    private final FeeScheduleRepository feeSchedules;
    private final SettlementRepository settlements;
    private final StripeGateway stripe;
    private final PaymentSplitter splitter;
    private final LedgerPoster ledger;
    private final IdempotencyStore idempotency;
    private final PaymentStatusUpdater statuses;
    private final WalletSync wallet;
    private final WalletRepository savedMethods;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final DomainEventPublisher events;
    private final PaymentsSettings settings;
    private final OrgPlanService orgPlans;
    private final LineRates lineRates;
    private final CreditLedger credit;
    private final Clock clock;

    PaymentServiceImpl(PaymentRepository payments, FeeScheduleRepository feeSchedules,
                       SettlementRepository settlements, StripeGateway stripe, PaymentSplitter splitter,
                       LedgerPoster ledger, IdempotencyStore idempotency, PaymentStatusUpdater statuses,
                       WalletSync wallet, WalletRepository savedMethods, MoneyTransactions tx, PaymentsAccess access,
                       DomainEventPublisher events, PaymentsSettings settings, OrgPlanService orgPlans,
                       LineRates lineRates, CreditLedger credit, Clock clock) {
        this.payments = payments;
        this.feeSchedules = feeSchedules;
        this.settlements = settlements;
        this.stripe = stripe;
        this.splitter = splitter;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.statuses = statuses;
        this.wallet = wallet;
        this.savedMethods = savedMethods;
        this.tx = tx;
        this.access = access;
        this.events = events;
        this.settings = settings;
        this.orgPlans = orgPlans;
        this.lineRates = lineRates;
        this.credit = credit;
        this.clock = clock;
    }

    @Override
    public PaymentResult create(CreatePaymentCommand raw, String idempotencyKey) {
        access.service();
        CreatePaymentCommand cmd = validate(raw);
        String fingerprint = Fingerprint.of(cmd);
        // Plans are read before the money transaction: a first sight of an organisation may ask the seller service.
        Map<String, String> plans = orgPlans.plansOf(LineRates.orgsOf(cmd.lines()));
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
            StripeCustomer saved = cmd.offSession() ? savedCustomer(cmd.shopperId()) : null;
            String stripeCustomer = saved != null ? saved.stripeCustomerId() : stripeCustomerFor(cmd);
            List<OrderLine> priced = lineRates.snapshot(schedule, cmd.lines(), placedAt, plans);
            Payment payment = Payment.create(paymentId, cmd.orderId(), stripeCustomer, cmd.amount(), schedule.id(),
                    placedAt, cmd.metadata(), priced, now);
            if (!payments.claim(payment)) {
                throw new PaymentsStateConflict("payments.payment-in-flight", "The payment of order "
                        + cmd.orderId() + " is being created; retry");
            }
            Map<String, String> metadata = new LinkedHashMap<>(cmd.metadata());
            metadata.put("order_id", cmd.orderId());
            metadata.put("payment_id", paymentId.toString());
            if (cmd.shopperId() != null && !cmd.shopperId().isBlank()) {
                metadata.put("afrotransact_customer_id", cmd.shopperId());
            }
            if (saved != null) {
                return offSessionCreate(payment, saved, metadata, idempotencyKey, now);
            }
            if (cmd.useCredit()) {
                payment = applyCredit(payment, cmd);
            }
            if (payment.paidWithCreditOnly()) {
                // Credit pays the whole order: there is no card to authorise, so the payment is authorised at once.
                Payment stored = payments.update(payment.authorisedByCredit(now));
                statuses.publish(PaymentsEvents.INTENT_CREATED, stored);
                statuses.publish(PaymentsEvents.INTENT_SUCCEEDED, stored);
                idempotency.complete("payment-create", idempotencyKey, new StoredRef(stored.id()));
                return new PaymentResult(stored, false);
            }
            metadata.put("credit_minor", Long.toString(payment.credit().minor()));
            IntentSnapshot intent = stripe.createIntent(new StripeGateway.CreateIntent(payment.cardAmount(),
                    payment.customerId(), cmd.paymentMethodTypes(), "AfroTransact order " + cmd.orderId(), metadata,
                    cmd.orderId(), cmd.saveForOffSession()), cmd.orderId() + ":create-intent");
            Payment stored = payments.update(payment.withIntent(intent.id(), intent.clientSecret(),
                    PaymentStatus.fromStripe(intent.status()), intent.multicapture(), now));
            statuses.publish(PaymentsEvents.INTENT_CREATED, stored);
            idempotency.complete("payment-create", idempotencyKey, new StoredRef(stored.id()));
            return new PaymentResult(stored, false);
        });
    }

    /**
     * Spends the shopper's credit on the order, first and in this transaction: the ledger locks the shopper's credit,
     * so two orders never spend the same credit, and the order id keys the spend, so a retried checkout never spends
     * twice. Credit leaves at least 0.50 for the card, or pays the whole order.
     */
    private Payment applyCredit(Payment payment, CreatePaymentCommand cmd) {
        if (cmd.shopperId() == null || cmd.shopperId().isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Credit belongs to a signed-in shopper",
                    Map.of("shopperId", "Required to use credit"));
        }
        CreditLedger.Spend spend = credit.spend(new CreditLedger.SpendCommand(cmd.shopperId(), cmd.amount(),
                MIN_CARD_MINOR, cmd.orderId(), cmd.shopperId(), "spend:" + cmd.orderId()));
        if (!spend.spent().isPositive()) {
            return payment;
        }
        return payment.withCredit(spend.spent(), cmd.shopperId());
    }

    /**
     * S272 late pre-order add, saved card: the intent is created and confirmed off-session on the shopper's default
     * saved card (manual capture), exactly like an off-session follow-up charge but as the order's primary payment.
     */
    private PaymentResult offSessionCreate(Payment payment, StripeCustomer customer, Map<String, String> metadata,
                                           String idempotencyKey, Instant now) {
        SavedPaymentMethod method = defaultCard(customer);
        metadata.put("off_session", "true");
        OffSessionAttempt attempt = stripe.createOffSessionIntent(new StripeGateway.OffSessionIntent(
                payment.amount(), customer.stripeCustomerId(), method.id(), "AfroTransact order " + payment.orderId()
                + " (off-session)", metadata, payment.orderId()), payment.orderId() + ":create-intent");
        IntentSnapshot intent = attempt.intent();
        Payment stored = payments.update(payment.withIntent(intent.id(), intent.clientSecret(),
                PaymentStatus.REQUIRES_PAYMENT_METHOD, intent.multicapture(), now));
        statuses.publish(PaymentsEvents.INTENT_CREATED, stored);
        if (attempt.authenticationRequired()) {
            stored = payments.update(stored.awaitingCustomer(OffSessionAttempt.AUTHENTICATION_REQUIRED, now));
            events.publish(PaymentsEvents.INTENT_ACTION_REQUIRED, PaymentsEvents.AGGREGATE_PAYMENT, stored.orderId(),
                    PaymentsEventPayloads.ActionRequiredPayload.of(stored, customer.customerId(),
                            OffSessionAttempt.AUTHENTICATION_REQUIRED));
        } else if (attempt.errorCode() != null) {
            String message = attempt.errorMessage() == null ? attempt.errorCode() : attempt.errorMessage();
            stored = statuses.apply(stored, PaymentStatus.FAILED, attempt.declineCode() == null ? message
                    : message + " (" + attempt.declineCode() + ")");
        } else {
            stored = statuses.apply(stored, PaymentStatus.fromStripe(intent.status()), intent.failureMessage());
        }
        idempotency.complete("payment-create", idempotencyKey, new StoredRef(stored.id()));
        return new PaymentResult(stored, false);
    }

    private StripeCustomer savedCustomer(String shopperId) {
        return savedMethods.findCustomer(shopperId).orElseThrow(() -> new PaymentsRuleViolation(
                "payments.no-saved-method", "Customer " + shopperId + " has no saved payment method"));
    }

    private SavedPaymentMethod defaultCard(StripeCustomer customer) {
        String methodId = customer.defaultPaymentMethodId();
        if (methodId == null) {
            throw new PaymentsRuleViolation("payments.no-saved-method", "Customer " + customer.customerId()
                    + " has no default payment method");
        }
        SavedPaymentMethod method = savedMethods.findMethod(methodId)
                .filter(m -> m.customerId().equals(customer.customerId()))
                .orElseThrow(() -> new NotFoundException("payments.payment-method-not-found",
                        "No saved payment method " + methodId));
        if (method.type() != PaymentMethodType.CARD) {
            throw new PaymentsRuleViolation("payments.method-not-chargeable-off-session", "Only saved cards can be "
                    + "charged off-session", Map.of("paymentMethodId", "Must be a card"));
        }
        return method;
    }

    private PaymentResult replayCreate(Payment existing, CreatePaymentCommand cmd, String idempotencyKey) {
        List<OrderLine> lines = existing.lines().stream().map(pl -> pl.line().withoutRate()).toList();
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
            Optional<Capture> earlier = capturedWithEarlierShipment(p, cmd.lineIds());
            if (earlier.isPresent()) {
                idempotency.complete(scope, idempotencyKey, new StoredRef(earlier.get().id()));
                return new CaptureResult(earlier.get(), p, true);
            }
            List<PaymentLine> requested = linesToCapture(p, cmd.lineIds());
            Money asked = requested.stream().map(pl -> pl.line().total()).reduce(Money.zero(p.currency()),
                    Money::plus);
            if (cmd.amount() != null && !cmd.amount().equals(asked)) {
                throw new PaymentsRuleViolation("payments.capture-amount-mismatch", "The shipment's lines add up to "
                        + asked + ", not " + cmd.amount(), Map.of("amountMinor", "Must equal the lines' total"));
            }
            if (!p.status().isCapturable()) {
                throw new PaymentsStateConflict("payments.not-capturable", "Payment of order " + p.orderId() + " is "
                        + p.status() + "; only AUTHORIZED or PARTIALLY_CAPTURED payments can be captured");
            }
            CapturePlan plan = CapturePlan.of(p, requested, cmd.finalCapture());
            List<OrderLine> lines = plan.lines().stream().map(PaymentLine::line).toList();
            Money amount = plan.amount(p.currency());
            boolean finalCapture = plan.finalCapture();
            UUID captureId = Fingerprint.id("capture", p.id().toString(), cmd.shipmentId());
            Payment capturable = p;
            // The part of the order paid with credit is not charged to the card: the card is captured for the rest.
            Money creditShare = p.creditShareOf(amount, creditCaptured(p));
            Money cardShare = amount.minus(creditShare);
            // A method charged when confirmed (US bank account / ACH: no manual capture) has nothing to capture at
            // Stripe; the shipment's capture is then recorded against the charge that already settled.
            String chargeId = null;
            if (cardShare.isPositive()) {
                IntentSnapshot live = stripe.retrieveIntent(p.stripePaymentIntentId());
                IntentSnapshot captured = chargedUpfront(live, p) ? live
                        : stripe.capture(p.stripePaymentIntentId(), cardShare, plan.stripeFinalCapture(),
                        Map.of("order_id", p.orderId(), "shipment_id", cmd.shipmentId()),
                        p.stripeKeyPrefix() + ":capture:" + cmd.shipmentId());
                chargeId = captured.latestChargeId();
            }
            FeeSchedule schedule = schedule(p);
            Fee fee = cardShare.isPositive() ? captureFee(p, chargeId, cardShare, schedule)
                    : new Fee(Money.zero(p.currency()), false);
            PaymentSplit split = splitter.split(lines, schedule, fee.amount());
            Instant now = Instant.now(clock);
            UUID journalId = Fingerprint.id("journal", "capture", captureId.toString());
            Journal journal = Postings.capture(journalId, now, "capture:" + captureId, p.orderId(),
                    p.id().toString(), chargeId, split, creditShare);
            ledger.post(journal, actor.subject());
            Capture capture = new Capture(captureId, p.id(), cmd.shipmentId(), amount, fee.amount(), fee.estimated(),
                    chargeId, finalCapture, lines.stream().map(OrderLine::lineId).toList(),
                    journalId, now, creditShare);
            payments.insertCapture(capture);
            Payment stored = payments.update(capturable.capture(captureId, amount, split.lines(), finalCapture, now));
            settlements.reopen(p.orderId(), now);
            events.publish(PaymentsEvents.CAPTURED, PaymentsEvents.AGGREGATE_PAYMENT, p.orderId(),
                    PaymentsEventPayloads.CapturedPayload.of(stored, capture, split));
            idempotency.complete(scope, idempotencyKey, new StoredRef(captureId));
            return new CaptureResult(capture, stored, false);
        });
    }

    /**
     * A single-capture order's first capture took every line (CapturePlan): a later shipment's lines were captured
     * with an earlier shipment, so its capture is that one (replayed). Empty when the lines are not captured yet.
     */
    private Optional<Capture> capturedWithEarlierShipment(Payment p, List<String> lineIds) {
        if (p.multicapture() || !p.capturedPerShipment()) {
            return Optional.empty();
        }
        List<Capture> done = payments.captures(p.id());
        if (lineIds.isEmpty()) {
            return p.lines().stream().allMatch(PaymentLine::isCaptured) && !done.isEmpty()
                    ? Optional.of(done.getLast()) : Optional.empty();
        }
        Set<String> wanted = new HashSet<>(lineIds);
        boolean allCaptured = lineIds.stream().allMatch(id -> p.line(id).map(PaymentLine::isCaptured).orElse(false));
        if (!allCaptured) {
            return Optional.empty();
        }
        return done.stream().filter(c -> c.lineIds().stream().anyMatch(wanted::contains)).findFirst();
    }

    private List<PaymentLine> linesToCapture(Payment p, List<String> lineIds) {
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
        return List.copyOf(chosen);
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
            returnUncapturedCredit(p, "Order " + p.orderId() + " was cancelled before it was paid");
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
                if (chargedUpfront(intent, p)) {
                    // Charged when confirmed (ACH): nothing is held at Stripe; giving the money back is a refund.
                    LOG.warn("Payment {} of order {} was charged up front; releasing {} needs a refund",
                            p.id(), p.orderId(), p.uncaptured());
                } else if (p.captured().isZero()) {
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
            returnUncapturedCredit(p, "The rest of order " + p.orderId() + " was released");
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
                        schedule.reverseCommissionOnRefund(), cmd.faultOrDefault());
                lines.add(rl);
                working = working.refund(new Refund(refundId, p.id(), p.orderId(), null, rl.total(),
                        RefundStatus.PENDING, null, List.of(rl), null, null), Instant.now(clock));
            }
            Money amount = lines.stream().map(RefundLine::total).reduce(Money.zero(p.currency()), Money::plus);
            if (!amount.isPositive()) {
                throw new PaymentsRuleViolation("payments.invalid-refund", "The refund amount must be positive");
            }
            Instant now = Instant.now(clock);
            // Each part goes back to what paid it: the credit part as credit, the card part to the card. Only staff
            // (with a reason) may take the card part as credit instead; never the other way round.
            Money creditPart = p.creditPartOf(amount, creditRefunded(p));
            boolean cardAsCredit = (cmd.cardAsCredit() || p.cardRefundsAsCredit())
                    && amount.minus(creditPart).isPositive();
            Money toCredit = cardAsCredit ? amount : creditPart;
            Money toCard = amount.minus(toCredit);
            if (toCredit.isPositive() && shopperOf(p) == null) {
                throw new PaymentsRuleViolation("payments.no-credit-account", "This order was not paid by a "
                        + "signed-in shopper, so there is no credit account to refund to",
                        Map.of("cardAsCredit", "Not available"));
            }
            StripeGateway.RefundSnapshot stripeRefund = toCard.isPositive()
                    ? stripe.refund(p.stripePaymentIntentId(), toCard,
                    Map.of("order_id", p.orderId(), "refund_id", refundId.toString()),
                    p.stripeKeyPrefix() + ":refund:" + refundId) : null;
            Refund refund = new Refund(refundId, p.id(), p.orderId(),
                    stripeRefund == null ? "credit:" + refundId : stripeRefund.id(), amount, RefundStatus.PENDING,
                    cmd.reason(), lines, now, null, creditPart, cardAsCredit);
            ledger.post(Postings.refund(Fingerprint.id("journal", "refund", refundId.toString()), now,
                    "refund:" + refundId, p.orderId(), p.id().toString(),
                    stripeRefund == null ? null : stripeRefund.id(), lines, settings.retailOrgId(), toCredit),
                    actor.subject());
            payments.insertRefund(refund);
            if (toCredit.isPositive()) {
                credit.add(new CreditLedger.Add(shopperOf(p), CreditKind.REFUND, toCredit, CreditSource.REFUND,
                        refundReason(p, cmd, cardAsCredit), cmd.chosenBy() != null && cardAsCredit ? cmd.chosenBy()
                        : actor.subject(), p.orderId(), refundId.toString(), "refund:" + refundId, false));
            }
            if (stripeRefund == null) {
                refund = refund.settled(now);
                payments.updateRefund(refund);
            } else if ("succeeded".equals(stripeRefund.status())) {
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
     * (capped at what is left of their transfers) with Stripe transfer reversals. AfroTransact Retail lines are
     * skipped: Retail money is never transferred, its refund only debits 4400 Retail sales.
     */
    private List<RefundResult.Reversal> reverseTransfers(Payment p, Refund refund, Instant now, String by) {
        Map<String, Money> debitByOrg = new LinkedHashMap<>();
        for (RefundLine l : refund.lines()) {
            if (!l.sellerOrg().equals(settings.retailOrgId())) {
                debitByOrg.merge(l.sellerOrg(), l.sellerDebit(), Money::plus);
            }
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
        if ((cmd.saveForOffSession() || cmd.offSession()) && (cmd.shopperId() == null
                || cmd.shopperId().isBlank())) {
            errors.put("shopperId", "Required to save or charge the payment method off-session");
        }
        if (!errors.isEmpty()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Invalid payment request", errors);
        }
        return new CreatePaymentCommand(cmd.orderId().trim(), cmd.customerId(), cmd.amount(),
                cmd.paymentMethodTypes(), cmd.placedAt(), cmd.metadata(), cmd.lines(),
                cmd.shopperId() == null ? null : cmd.shopperId().trim(), cmd.saveForOffSession(), cmd.offSession(),
                cmd.useCredit());
    }

    private static NotFoundException notFound(String what) {
        return new NotFoundException("payments.payment-not-found", "No payment " + what);
    }

    /**
     * The Stripe Customer on the PaymentIntent: created for off-session saving; otherwise the one given, else the
     * shopper's existing Customer (S245), so the mobile PaymentSheet (ephemeral key for that Customer) lists their
     * saved cards. Checkout never creates a Customer just to show an empty wallet.
     */
    private String stripeCustomerFor(CreatePaymentCommand cmd) {
        if (cmd.saveForOffSession()) {
            return wallet.ensureCustomer(cmd.shopperId()).stripeCustomerId();
        }
        if (cmd.customerId() != null && !cmd.customerId().isBlank()) {
            return cmd.customerId();
        }
        if (cmd.shopperId() != null && !cmd.shopperId().isBlank()) {
            return wallet.stripeCustomerOf(cmd.shopperId()).orElse(null);
        }
        return null;
    }

    /** Stripe fee of a capture. */
    private record Fee(Money amount, boolean estimated) {
    }

    /** The intent was charged in full when confirmed (a method without manual capture, e.g. US bank account). */
    private static boolean chargedUpfront(IntentSnapshot intent, Payment p) {
        return intent != null && "succeeded".equals(intent.status()) && intent.amountCapturable() == 0
                && intent.amountReceived() >= p.cardAmount().minor();
    }

    /** The credit that the captures of this payment already took. */
    private Money creditCaptured(Payment p) {
        return payments.captures(p.id()).stream().map(Capture::credit).reduce(Money.zero(p.currency()), Money::plus);
    }

    /** The credit part of the earlier refunds of this payment (what originally paid with credit and went back). */
    private Money creditRefunded(Payment p) {
        return payments.refunds(p.id()).stream().map(Refund::credit).reduce(Money.zero(p.currency()), Money::plus);
    }

    /**
     * Credit that was spent on an order but never captured (the order was cancelled, or the rest of its authorisation
     * released) goes back to the shopper. It was never revenue, so nothing is booked in the general ledger. Keyed by
     * the order, so a replay returns it once.
     */
    private void returnUncapturedCredit(Payment p, String reason) {
        if (!p.credit().isPositive() || shopperOf(p) == null) {
            return;
        }
        Money left = p.credit().minus(creditCaptured(p));
        if (left.isPositive()) {
            credit.add(new CreditLedger.Add(shopperOf(p), CreditKind.REFUND, left, CreditSource.REFUND, reason,
                    "system:payments", p.orderId(), p.id().toString(), "return:" + p.orderId(), false));
        }
    }

    /** The customer whose credit this order used or is refunded to: the payment's shopper, else the order's customer. */
    private static String shopperOf(Payment p) {
        if (p.shopperId() != null && !p.shopperId().isBlank()) {
            return p.shopperId();
        }
        String fromOrder = p.metadata().get("customer_id");
        return fromOrder == null || fromOrder.isBlank() ? p.metadata().get("afrotransact_customer_id") : fromOrder;
    }

    private static String refundReason(Payment p, RefundCommand cmd, boolean cardAsCredit) {
        String base = "Refund on order " + p.orderId();
        if (cardAsCredit) {
            base += ", card part taken as credit" + (cmd.chosenBy() == null ? "" : " by " + cmd.chosenBy());
        }
        return cmd.reason() == null || cmd.reason().isBlank() ? base : base + ": " + cmd.reason();
    }
}
