package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentMethodType;
import com.afrotransact.payments.domain.PaymentStatus;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.IntentSnapshot;
import com.afrotransact.payments.port.StripeGateway.OffSessionAttempt;
import com.afrotransact.payments.port.WalletRepository;
import com.afrotransact.payments.port.WalletRepository.StripeCustomer;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class OffSessionChargeServiceImpl implements OffSessionChargeService {

    private static final String SCOPE = "off-session";

    private final PaymentRepository payments;
    private final WalletRepository wallet;
    private final StripeGateway stripe;
    private final IdempotencyStore idempotency;
    private final PaymentStatusUpdater statuses;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final DomainEventPublisher events;
    private final Clock clock;

    OffSessionChargeServiceImpl(PaymentRepository payments, WalletRepository wallet, StripeGateway stripe,
                                IdempotencyStore idempotency, PaymentStatusUpdater statuses, MoneyTransactions tx,
                                PaymentsAccess access, DomainEventPublisher events, Clock clock) {
        this.payments = payments;
        this.wallet = wallet;
        this.stripe = stripe;
        this.idempotency = idempotency;
        this.statuses = statuses;
        this.tx = tx;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public PaymentResult charge(UUID paymentId, OffSessionCommand cmd, String idempotencyKey) {
        access.service();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required",
                    "Off-session charges need an Idempotency-Key");
        }
        if (cmd.customerId() == null || cmd.customerId().isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "customerId is required",
                    Map.of("customerId", "Required"));
        }
        String fingerprint = Fingerprint.of(paymentId + "|" + cmd);
        return tx.serializable(() -> {
            Optional<StoredRef> prior = idempotency.claim(SCOPE, idempotencyKey, fingerprint, StoredRef.class);
            if (prior.isPresent()) {
                return new PaymentResult(payments.findById(prior.get().id()).orElseThrow(), true);
            }
            Payment parent = payments.lockById(paymentId).orElseThrow(() -> new NotFoundException(
                    "payments.payment-not-found", "No payment " + paymentId));
            requireChargeableParent(parent);
            List<OrderLine> lines = linesToCharge(parent, cmd.lineIds());
            requireNotChargedElsewhere(parent, lines);
            StripeCustomer customer = wallet.findCustomer(cmd.customerId().trim()).orElseThrow(() ->
                    new PaymentsRuleViolation("payments.no-saved-method", "Customer " + cmd.customerId()
                            + " has no saved payment method"));
            if (parent.customerId() != null && parent.customerId().startsWith("cus_")
                    && !parent.customerId().equals(customer.stripeCustomerId())) {
                throw new PaymentsRuleViolation("payments.customer-mismatch", "Order " + parent.orderId()
                        + " was paid by another customer");
            }
            SavedPaymentMethod method = method(customer, cmd.paymentMethodId());
            Instant now = Instant.now(clock);
            UUID id = Fingerprint.id("payment", parent.orderId(), "off-session", parent.id().toString(),
                    idempotencyKey);
            Map<String, String> metadata = new LinkedHashMap<>(parent.metadata());
            metadata.put("order_id", parent.orderId());
            metadata.put("payment_id", id.toString());
            metadata.put("parent_payment_id", parent.id().toString());
            metadata.put("afrotransact_customer_id", customer.customerId());
            metadata.put("off_session", "true");
            Payment child = Payment.offSession(id, parent, customer.stripeCustomerId(), lines, metadata, now);
            if (!payments.claim(child)) {
                throw new PaymentsStateConflict("payments.payment-in-flight", "This off-session charge is being "
                        + "created; retry");
            }
            OffSessionAttempt attempt = stripe.createOffSessionIntent(new StripeGateway.OffSessionIntent(
                    child.amount(), customer.stripeCustomerId(), method.id(), "AfroTransact order "
                    + parent.orderId() + " (off-session)", metadata, parent.orderId()),
                    child.stripeKeyPrefix() + ":create-intent");
            IntentSnapshot intent = attempt.intent();
            Payment stored = payments.update(child.withIntent(intent.id(), intent.clientSecret(),
                    PaymentStatus.REQUIRES_PAYMENT_METHOD, now));
            statuses.publish(PaymentsEvents.INTENT_CREATED, stored);
            if (attempt.authenticationRequired()) {
                stored = payments.update(stored.awaitingCustomer(OffSessionAttempt.AUTHENTICATION_REQUIRED, now));
                events.publish(PaymentsEvents.INTENT_ACTION_REQUIRED, PaymentsEvents.AGGREGATE_PAYMENT,
                        stored.orderId(), PaymentsEventPayloads.ActionRequiredPayload.of(stored,
                                customer.customerId(), OffSessionAttempt.AUTHENTICATION_REQUIRED));
            } else if (attempt.errorCode() != null) {
                stored = statuses.apply(stored, PaymentStatus.FAILED, failure(attempt));
            } else {
                stored = statuses.apply(stored, PaymentStatus.fromStripe(intent.status()), intent.failureMessage());
            }
            idempotency.complete(SCOPE, idempotencyKey, new StoredRef(stored.id()));
            return new PaymentResult(stored, false);
        });
    }

    /** Only the order's primary payment, and only once its own authorisation no longer covers anything. */
    private static void requireChargeableParent(Payment parent) {
        if (parent.isOffSession()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Charge lines of the order's primary "
                    + "payment, not of an off-session charge");
        }
        if (!parent.status().isFinal()) {
            throw new PaymentsStateConflict("payments.authorization-still-held", "Payment of order "
                    + parent.orderId() + " is " + parent.status() + "; capture it or release the remaining "
                    + "authorisation (POST /v1/payments/{id}/release) before charging off-session");
        }
    }

    private static List<OrderLine> linesToCharge(Payment parent, List<String> lineIds) {
        List<PaymentLine> chosen = new ArrayList<>();
        if (lineIds.isEmpty()) {
            parent.lines().stream().filter(l -> !l.isCaptured()).forEach(chosen::add);
        } else {
            for (String lineId : new LinkedHashSet<>(lineIds)) {
                PaymentLine pl = parent.line(lineId).orElseThrow(() -> new PaymentsRuleViolation(
                        "payments.unknown-line", "Order " + parent.orderId() + " has no line " + lineId,
                        Map.of("lineIds", "Unknown line " + lineId)));
                if (pl.isCaptured()) {
                    throw new PaymentsStateConflict("payments.line-already-captured", "Line " + lineId
                            + " was already captured");
                }
                chosen.add(pl);
            }
        }
        if (chosen.isEmpty()) {
            throw new PaymentsStateConflict("payments.nothing-to-charge", "Every line of order " + parent.orderId()
                    + " is already captured");
        }
        return chosen.stream().map(PaymentLine::line).toList();
    }

    /** A line may have at most one live (not failed / cancelled) off-session charge. */
    private void requireNotChargedElsewhere(Payment parent, List<OrderLine> lines) {
        Set<String> wanted = new HashSet<>();
        lines.forEach(l -> wanted.add(l.lineId()));
        for (Payment child : payments.children(parent.id())) {
            if (child.status() == PaymentStatus.FAILED
                    || (child.status() == PaymentStatus.CANCELED && child.captured().isZero())) {
                continue;
            }
            for (PaymentLine l : child.lines()) {
                if (wanted.contains(l.lineId())) {
                    throw new PaymentsStateConflict("payments.lines-already-charged", "Line " + l.lineId()
                            + " already has off-session charge " + child.id() + " (" + child.status()
                            + "); cancel it first to retry");
                }
            }
        }
    }

    private SavedPaymentMethod method(StripeCustomer customer, String requested) {
        String methodId = requested == null || requested.isBlank() ? customer.defaultPaymentMethodId()
                : requested.trim();
        if (methodId == null) {
            throw new PaymentsRuleViolation("payments.no-saved-method", "Customer " + customer.customerId()
                    + " has no default payment method");
        }
        SavedPaymentMethod method = wallet.findMethod(methodId)
                .filter(m -> m.customerId().equals(customer.customerId()))
                .orElseThrow(() -> new NotFoundException("payments.payment-method-not-found",
                        "No saved payment method " + methodId));
        if (method.type() != PaymentMethodType.CARD) {
            throw new PaymentsRuleViolation("payments.method-not-chargeable-off-session", "Only saved cards can be "
                    + "authorised off-session for later capture", Map.of("paymentMethodId", "Must be a card"));
        }
        return method;
    }

    private static String failure(OffSessionAttempt attempt) {
        String message = attempt.errorMessage() == null ? attempt.errorCode() : attempt.errorMessage();
        return attempt.declineCode() == null ? message : message + " (" + attempt.declineCode() + ")";
    }
}
