package com.afrotransact.payments.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Seller plan fees are PaymentIntents on the platform account (metadata {@code purpose=plan_fee}, card or US bank debit)
 * and the plan SetupIntents ({@code purpose=seller_billing}). Their Stripe events arrive at the one platform endpoint
 * ({@code /payments/v1/webhooks/stripe}); payments must not treat them as order payments. This hands each one to seller
 * as {@code payments.plan_fee.stripe_event} (topic at.payment.events, through the outbox in the inbox transaction, key =
 * the plan invoice id, else the PaymentIntent / SetupIntent id). Seller de-duplicates on {@code stripeEventId}.
 *
 * <p>A {@code charge.*} or {@code charge.dispute.*} object without the purpose in its own metadata is matched through its
 * PaymentIntent: when no order payment has that intent, the intent's metadata (read from Stripe) decides.
 */
@Component
class PlanFeeEventForwarder {

    static final String PLAN_FEE = "plan_fee";
    static final String SELLER_BILLING = "seller_billing";
    /** Event types that can concern a plan fee (all are on the platform endpoint's list). */
    static final Set<String> TYPES = Set.of("payment_intent.succeeded", "payment_intent.processing",
            "payment_intent.payment_failed", "payment_intent.requires_action", "payment_intent.canceled",
            "charge.refunded", "charge.dispute.created", "charge.dispute.closed", "charge.dispute.funds_withdrawn",
            "setup_intent.succeeded");

    private static final Logger LOG = LoggerFactory.getLogger(PlanFeeEventForwarder.class);

    private final PaymentRepository payments;
    private final StripeGateway stripe;
    private final DomainEventPublisher events;

    PlanFeeEventForwarder(PaymentRepository payments, StripeGateway stripe, DomainEventPublisher events) {
        this.payments = payments;
        this.stripe = stripe;
        this.events = events;
    }

    /** Publishes the event for seller and returns true when it is a plan-fee event; false leaves it to payments. */
    boolean forward(String eventId, String type, long created, JsonNode object) {
        if (!TYPES.contains(type)) {
            return false;
        }
        Optional<String> purpose = purpose(type, object);
        if (purpose.isEmpty()) {
            return false;
        }
        PaymentsEventPayloads.PlanFeeStripeEvent payload = payload(eventId, type, created, purpose.get(), object);
        String key = payload.invoiceId() != null ? payload.invoiceId()
                : payload.paymentIntentId() != null ? payload.paymentIntentId() : payload.setupIntentId();
        events.publish(PaymentsEvents.PLAN_FEE_STRIPE_EVENT, PaymentsEvents.AGGREGATE_PAYMENT, key, payload);
        LOG.info("Stripe event {} ({}) is a seller {}: handed to seller", eventId, type, purpose.get());
        return true;
    }

    private Optional<String> purpose(String type, JsonNode object) {
        String own = text(object.path("metadata"), "purpose");
        if (type.startsWith("setup_intent.")) {
            return SELLER_BILLING.equals(own) ? Optional.of(SELLER_BILLING) : Optional.empty();
        }
        if (PLAN_FEE.equals(own)) {
            return Optional.of(PLAN_FEE);
        }
        if (type.startsWith("payment_intent.") || own != null) {
            return Optional.empty();
        }
        String intent = text(object, "payment_intent");
        if (intent == null || payments.findByIntentId(intent).isPresent()) {
            return Optional.empty();
        }
        Map<String, String> metadata = stripe.intentMetadata(intent);
        return metadata != null && PLAN_FEE.equals(metadata.get("purpose")) ? Optional.of(PLAN_FEE)
                : Optional.empty();
    }

    private static PaymentsEventPayloads.PlanFeeStripeEvent payload(String eventId, String type, long created,
                                                                    String purpose, JsonNode o) {
        String objectType = text(o, "object");
        JsonNode err = o.path("last_payment_error");
        String intentId = switch (objectType == null ? "" : objectType) {
            case "payment_intent" -> text(o, "id");
            case "setup_intent" -> null;
            default -> text(o, "payment_intent");
        };
        String chargeId = "charge".equals(objectType) ? text(o, "id")
                : "dispute".equals(objectType) ? text(o, "charge") : text(o, "latest_charge");
        String failureCode = err.isMissingNode() || err.isNull() ? text(o, "failure_code") : text(err, "code");
        String failureMessage = err.isMissingNode() || err.isNull() ? text(o, "failure_message")
                : text(err, "message");
        boolean dispute = "dispute".equals(objectType);
        boolean setup = "setup_intent".equals(objectType);
        return new PaymentsEventPayloads.PlanFeeStripeEvent(eventId, type, created, purpose, objectType, intentId,
                text(o.path("metadata"), "invoice_id"), text(o.path("metadata"), "org_id"),
                dispute ? null : text(o, "status"), o.path("amount").asLong(0), o.path("amount_received").asLong(0),
                o.path("amount_refunded").asLong(0), text(o, "currency"), chargeId, failureCode, failureMessage,
                text(o.path("next_action"), "type"), dispute ? text(o, "id") : null,
                dispute ? text(o, "status") : null, dispute ? text(o, "reason") : null,
                setup ? text(o, "id") : null, setup || "payment_intent".equals(objectType)
                        ? text(o, "payment_method") : null,
                setup ? text(o, "mandate") : null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            return value.path("id").isMissingNode() ? null : value.path("id").asString(null);
        }
        return value.asString(null);
    }
}
