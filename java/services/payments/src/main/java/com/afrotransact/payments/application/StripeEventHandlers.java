package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.payments.domain.Dispute;
import com.afrotransact.payments.domain.DisputeStatus;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentSplitter;
import com.afrotransact.payments.domain.PaymentStatus;
import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.RefundStatus;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.port.DisputeRepository;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.PayoutRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.WebhookInbox.InboxEvent;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Applies Stripe events from the inbox. Every handler is idempotent (journal keys, state checks). */
@Component
class StripeEventHandlers {

    static final Set<String> SUPPORTED = Set.of(
            "payment_intent.succeeded", "payment_intent.amount_capturable_updated", "payment_intent.payment_failed",
            "payment_intent.requires_action", "payment_intent.canceled", "charge.refunded",
            "charge.dispute.created", "charge.dispute.closed", "transfer.reversed", "payout.paid", "payout.failed",
            "setup_intent.succeeded", "setup_intent.requires_action", "payment_method.attached",
            "payment_method.detached");

    private static final Logger LOG = LoggerFactory.getLogger(StripeEventHandlers.class);
    private static final String SYSTEM = "system:stripe-webhook";

    private final PaymentRepository payments;
    private final DisputeRepository disputes;
    private final SettlementRepository settlements;
    private final PayoutRepository payouts;
    private final PaymentStatusUpdater statuses;
    private final WalletSync wallet;
    private final StripeGateway stripe;
    private final LedgerPoster ledger;
    private final DomainEventPublisher events;
    private final JsonMapper json;
    private final PaymentsSettings settings;
    private final Clock clock;

    StripeEventHandlers(PaymentRepository payments, DisputeRepository disputes, SettlementRepository settlements,
                        PayoutRepository payouts, PaymentStatusUpdater statuses, WalletSync wallet,
                        StripeGateway stripe, LedgerPoster ledger,
                        DomainEventPublisher events, JsonMapper json, PaymentsSettings settings, Clock clock) {
        this.payments = payments;
        this.disputes = disputes;
        this.settlements = settlements;
        this.payouts = payouts;
        this.statuses = statuses;
        this.wallet = wallet;
        this.stripe = stripe;
        this.ledger = ledger;
        this.events = events;
        this.json = json;
        this.settings = settings;
        this.clock = clock;
    }

    boolean supports(String type) {
        return SUPPORTED.contains(type);
    }

    void handle(InboxEvent event) {
        JsonNode object = json.readTree(event.payload()).path("data").path("object");
        switch (event.type()) {
            case "payment_intent.succeeded", "payment_intent.amount_capturable_updated" ->
                    intent(object, PaymentStatus.fromStripe(text(object, "status")), null);
            case "payment_intent.payment_failed" -> intent(object, PaymentStatus.FAILED, failure(object));
            case "payment_intent.requires_action" -> intent(object, PaymentStatus.REQUIRES_ACTION, null);
            case "payment_intent.canceled" -> intent(object, PaymentStatus.CANCELED, null);
            case "charge.refunded" -> chargeRefunded(object);
            case "charge.dispute.created" -> disputeCreated(object);
            case "charge.dispute.closed" -> disputeClosed(object);
            case "transfer.reversed" -> transferReversed(object);
            case "payout.paid" -> payout(object, true);
            case "payout.failed" -> payout(object, false);
            case "setup_intent.succeeded" -> setupIntent(object, true);
            case "setup_intent.requires_action" -> setupIntent(object, false);
            case "payment_method.attached" -> wallet.upsert(paymentMethod(object),
                    !"us_bank_account".equals(text(object, "type")));
            case "payment_method.detached" -> wallet.detached(text(object, "id"));
            default -> LOG.debug("Ignoring Stripe event type {}", event.type());
        }
    }

    private void intent(JsonNode pi, PaymentStatus reported, String failure) {
        Optional<Payment> payment = payments.lockByIntentId(text(pi, "id"));
        if (payment.isEmpty()) {
            payment = paidLink(pi);
        }
        if (payment.isEmpty()) {
            LOG.info("PaymentIntent {} is not an AfroTransact order payment; ignored", text(pi, "id"));
            return;
        }
        if (reported == PaymentStatus.FAILED && "authentication_required".equals(text(pi.path("last_payment_error"),
                "code")) && statuses.awaitingCustomer(payment.get(), text(pi, "status"))) {
            LOG.info("Off-session PaymentIntent {} waits for customer authentication", text(pi, "id"));
            return;
        }
        statuses.apply(payment.get(), reported, failure);
    }

    /**
     * S272: the PaymentIntent of a paid hosted link is created by Stripe; its metadata names our payment, which waits
     * without an intent. The intent is attached on its first event.
     */
    private Optional<Payment> paidLink(JsonNode pi) {
        String paymentId = text(pi.path("metadata"), "payment_id");
        if (paymentId == null) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(paymentId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (payments.findLink(id).isEmpty()) {
            return Optional.empty();
        }
        return payments.lockById(id).filter(p -> p.stripePaymentIntentId() == null)
                .map(p -> payments.update(p.withIntent(text(pi, "id"), null, p.status(), Instant.now(clock))));
    }

    private static String failure(JsonNode pi) {
        JsonNode error = pi.path("last_payment_error");
        String message = text(error, "message");
        String decline = text(error, "decline_code");
        if (message == null) {
            return decline == null ? "Payment failed" : decline;
        }
        return decline == null ? message : message + " (" + decline + ")";
    }

    /** Settles our pending refunds of the charge in creation order while Stripe's amount_refunded covers them. */
    private void chargeRefunded(JsonNode charge) {
        Optional<Payment> payment = payments.lockByIntentId(text(charge, "payment_intent"));
        if (payment.isEmpty()) {
            return;
        }
        long refundedByStripe = charge.path("amount_refunded").asLong();
        List<Refund> refunds = payments.refunds(payment.get().id()).stream()
                .sorted(Comparator.comparing(Refund::createdAt)).toList();
        long settled = refunds.stream().filter(r -> r.status() == RefundStatus.SUCCEEDED)
                .mapToLong(r -> r.amount().minor()).sum();
        Instant now = Instant.now(clock);
        for (Refund r : refunds) {
            if (r.status() != RefundStatus.PENDING || settled + r.amount().minor() > refundedByStripe) {
                continue;
            }
            ledger.post(Postings.refundSettled(Fingerprint.id("journal", "refund-settled", r.id().toString()), now,
                    "refund-settled:" + r.id(), r), SYSTEM);
            payments.updateRefund(r.settled(now));
            settled += r.amount().minor();
        }
        long ours = refunds.stream().mapToLong(r -> r.amount().minor()).sum();
        if (refundedByStripe > ours) {
            LOG.warn("Charge {} has {} refunded but AfroTransact issued {}: refund made outside AfroTransact",
                    text(charge, "id"), refundedByStripe, ours);
        }
    }

    private void disputeCreated(JsonNode d) {
        Optional<Payment> payment = payments.lockByIntentId(text(d, "payment_intent"));
        if (payment.isEmpty()) {
            LOG.warn("Dispute {} on unknown PaymentIntent {}", text(d, "id"), text(d, "payment_intent"));
            return;
        }
        Payment p = payment.get();
        String currency = p.currency();
        Money amount = Money.of(d.path("amount").asLong(), currency);
        long fee = 0;
        for (JsonNode bt : d.path("balance_transactions")) {
            fee += bt.path("fee").asLong();
        }
        Instant now = Instant.now(clock);
        Dispute dispute = new Dispute(text(d, "id"), p.id(), p.orderId(), text(d, "charge"), amount,
                Money.of(fee, currency), DisputeStatus.OPEN, text(d, "reason"),
                Dispute.allocate(amount, chargedByRecipient(p)), now, null);
        if (!disputes.insert(dispute)) {
            return;
        }
        ledger.post(Postings.disputeOpened(Fingerprint.id("journal", "dispute-opened", dispute.id()), now,
                "dispute-opened:" + dispute.id(), dispute, settings.retailOrgId()), SYSTEM);
        events.publish(PaymentsEvents.DISPUTE_OPENED, PaymentsEvents.AGGREGATE_PAYMENT, p.orderId(),
                PaymentsEventPayloads.DisputePayload.of(dispute));
    }

    /**
     * Captured, not refunded amounts per recipient: sellers (and AfroTransact Retail) carry items + tax + own shipping.
     */
    private static Map<String, Money> chargedByRecipient(Payment p) {
        Map<String, Money> charged = new LinkedHashMap<>();
        Money zero = Money.zero(p.currency());
        charged.put(PaymentSplitter.PLATFORM, zero);
        for (PaymentLine pl : p.lines()) {
            if (!pl.isCaptured()) {
                continue;
            }
            Money items = pl.line().items().minus(pl.refundedItems()).plus(pl.line().tax().minus(pl.refundedTax()));
            Money shipping = pl.line().shipping().minus(pl.refundedShipping());
            if (pl.shippingToPlatform()) {
                charged.merge(PaymentSplitter.PLATFORM, shipping, Money::plus);
                charged.merge(pl.line().sellerOrg(), items, Money::plus);
            } else {
                charged.merge(pl.line().sellerOrg(), items.plus(shipping), Money::plus);
            }
        }
        return charged;
    }

    private void disputeClosed(JsonNode d) {
        String id = text(d, "id");
        Dispute open = disputes.lock(id).orElseThrow(() -> new IllegalStateException("Dispute " + id
                + " closed before its creation was processed; will retry"));
        if (open.status() != DisputeStatus.OPEN) {
            return;
        }
        String status = text(d, "status");
        DisputeStatus outcome = "won".equals(status) || "warning_closed".equals(status) ? DisputeStatus.WON
                : DisputeStatus.LOST;
        Instant now = Instant.now(clock);
        Dispute closed = open.close(outcome, now);
        if (outcome == DisputeStatus.WON) {
            ledger.post(Postings.disputeWon(Fingerprint.id("journal", "dispute-won", id), now, "dispute-won:" + id,
                    closed, settings.retailOrgId()), SYSTEM);
        } else {
            ledger.post(Postings.disputeLost(Fingerprint.id("journal", "dispute-lost", id), now,
                    "dispute-lost:" + id, closed, settings.retailOrgId()), SYSTEM);
        }
        disputes.update(closed);
        events.publish(PaymentsEvents.DISPUTE_CLOSED, PaymentsEvents.AGGREGATE_PAYMENT, closed.orderId(),
                PaymentsEventPayloads.DisputePayload.of(closed));
    }

    /** Reversals AfroTransact did not initiate (e.g. from the Dashboard) are booked back to the seller payable. */
    private void transferReversed(JsonNode transfer) {
        Optional<SettlementTransfer> found = settlements.lockTransferByStripeId(text(transfer, "id"));
        if (found.isEmpty()) {
            return;
        }
        SettlementTransfer t = found.get();
        String currency = t.amount().currency();
        Instant now = Instant.now(clock);
        JsonNode reversals = transfer.path("reversals").path("data");
        if (reversals.isArray() && !reversals.isEmpty()) {
            for (JsonNode rev : reversals) {
                String revId = text(rev, "id");
                if (revId == null || settlements.reversalExists(revId)) {
                    continue;
                }
                Money amount = Money.of(rev.path("amount").asLong(), currency);
                bookReversal(t, revId, amount, now);
                t = t.reversedBy(amount);
            }
        } else {
            long diff = transfer.path("amount_reversed").asLong() - t.reversed().minor();
            if (diff > 0) {
                String ref = t.stripeTransferId() + ":" + transfer.path("amount_reversed").asLong();
                Money amount = Money.of(diff, currency);
                bookReversal(t, ref, amount, now);
                t = t.reversedBy(amount);
            }
        }
        settlements.updateTransfer(t);
    }

    private void bookReversal(SettlementTransfer t, String ref, Money amount, Instant now) {
        settlements.insertReversal(t.id(), ref, amount, null, now);
        ledger.post(Postings.transferReversal(Fingerprint.id("journal", "reversal", ref), now, "reversal:" + ref,
                t.orderId(), t.orgId(), amount, ref), SYSTEM);
    }

    private void payout(JsonNode payout, boolean paid) {
        Optional<PayoutRequest> found = payouts.lockByStripeId(text(payout, "id"));
        if (found.isEmpty()) {
            LOG.info("Payout {} was not requested through AfroTransact; ignored", text(payout, "id"));
            return;
        }
        PayoutRequest before = found.get();
        PayoutRequest after = paid ? before.paid() : before.failed(Optional.ofNullable(text(payout,
                "failure_message")).orElse(Optional.ofNullable(text(payout, "failure_code")).orElse("failed")));
        if (after.status() == before.status()) {
            return;
        }
        PayoutRequest stored = payouts.update(after);
        events.publish(paid ? PaymentsEvents.PAYOUT_PAID : PaymentsEvents.PAYOUT_FAILED,
                PaymentsEvents.AGGREGATE_PAYOUT, stored.orgId(), PaymentsEventPayloads.PayoutPayload.of(stored));
    }

    /**
     * A succeeded SetupIntent saved a verified method; one waiting for microdeposits is listed as pending
     * verification.
     */
    private void setupIntent(JsonNode si, boolean succeeded) {
        String pmId = text(si, "payment_method");
        if (pmId == null) {
            return;
        }
        if (!succeeded && !"verify_with_microdeposits".equals(si.path("next_action").path("type").asString())) {
            return;
        }
        StripeGateway.PaymentMethodSnapshot pm = stripe.retrievePaymentMethod(pmId);
        String customer = pm.customer() != null ? pm.customer() : text(si, "customer");
        wallet.upsert(new StripeGateway.PaymentMethodSnapshot(pm.id(), pm.type(), customer, pm.brand(), pm.last4(),
                pm.expMonth(), pm.expYear(), pm.bankName(), pm.accountType()), succeeded);
    }

    /** Display data of a PaymentMethod object in a webhook payload. */
    static StripeGateway.PaymentMethodSnapshot paymentMethod(JsonNode pm) {
        JsonNode card = pm.path("card");
        JsonNode bank = pm.path("us_bank_account");
        boolean isCard = card.isObject();
        return new StripeGateway.PaymentMethodSnapshot(text(pm, "id"), text(pm, "type"), text(pm, "customer"),
                isCard ? text(card, "brand") : null,
                isCard ? text(card, "last4") : bank.isObject() ? text(bank, "last4") : null,
                isCard && card.hasNonNull("exp_month") ? card.path("exp_month").asInt() : null,
                isCard && card.hasNonNull("exp_year") ? card.path("exp_year").asInt() : null,
                bank.isObject() ? text(bank, "bank_name") : null,
                bank.isObject() ? text(bank, "account_type") : null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            return text(value, "id");
        }
        return value.asString();
    }
}
