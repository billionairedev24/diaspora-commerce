package com.afrotransact.seller.it;

import java.util.List;
import java.util.stream.Collectors;

/** Builds Stripe webhook event JSON for tests. */
final class StripeEvents {

    private StripeEvents() {
    }

    static String event(String id, String type, long created, String account, String object) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\",\"created\":"
                + created + (account == null ? "" : ",\"account\":\"" + account + "\"")
                + ",\"livemode\":false,\"pending_webhooks\":1,\"type\":\"" + type + "\",\"data\":{\"object\":" + object
                + "}}";
    }

    static String account(String accountId, List<String> currentlyDue, List<String> pastDue,
                          List<String> pending, Long deadline, String disabledReason, boolean charges) {
        return "{\"id\":\"" + accountId + "\",\"object\":\"account\",\"charges_enabled\":" + charges
                + ",\"payouts_enabled\":" + pastDue.isEmpty() + ",\"details_submitted\":true,"
                + "\"capabilities\":{\"card_payments\":\"" + (charges ? "active" : "inactive") + "\"},"
                + "\"requirements\":{\"currently_due\":" + arr(currentlyDue) + ",\"eventually_due\":[],\"past_due\":"
                + arr(pastDue) + ",\"pending_verification\":" + arr(pending) + ",\"errors\":[],\"current_deadline\":"
                + (deadline == null ? "null" : deadline) + ",\"disabled_reason\":"
                + (disabledReason == null ? "null" : "\"" + disabledReason + "\"") + "}}";
    }

    /** payment_intent.succeeded / payment_intent.payment_failed of a plan fee. */
    static String planPayment(String intentId, String invoiceId, String error) {
        return "{\"id\":\"" + intentId + "\",\"object\":\"payment_intent\",\"status\":\""
                + (error == null ? "succeeded" : "requires_payment_method") + "\",\"metadata\":{\"purpose\":"
                + "\"plan_fee\",\"invoice_id\":\"" + invoiceId + "\"}"
                + (error == null ? "" : ",\"last_payment_error\":{\"code\":\"account_closed\",\"message\":\""
                + error + "\"}") + "}";
    }

    private static String arr(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
    }
}
