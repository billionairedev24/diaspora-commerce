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

    static String subscription(String id, String customer, String status, String lookupKey, long periodEnd) {
        return "{\"id\":\"" + id + "\",\"object\":\"subscription\",\"customer\":\"" + customer + "\",\"status\":\""
                + status + "\",\"cancel_at_period_end\":false,\"trial_end\":null,\"items\":{\"object\":\"list\","
                + "\"data\":[{\"id\":\"si_hook\",\"object\":\"subscription_item\",\"current_period_start\":"
                + (periodEnd - 30L * 86400) + ",\"current_period_end\":" + periodEnd + ",\"price\":{\"id\":\"price_x\","
                + "\"object\":\"price\",\"lookup_key\":\"" + lookupKey + "\"}}]}}";
    }

    static String invoice(String id, String customer, String status, long amount, int attempts, Long nextAttempt) {
        long now = System.currentTimeMillis() / 1000;
        return "{\"id\":\"" + id + "\",\"object\":\"invoice\",\"customer\":\"" + customer + "\",\"number\":\"INV-"
                + id + "\",\"status\":\"" + status + "\",\"billing_reason\":\"subscription_cycle\",\"amount_due\":"
                + amount + ",\"amount_paid\":" + ("paid".equals(status) ? amount : 0) + ",\"currency\":\"usd\","
                + "\"period_start\":" + (now - 30L * 86400) + ",\"period_end\":" + now + ",\"attempt_count\":"
                + attempts + ",\"next_payment_attempt\":" + (nextAttempt == null ? "null" : nextAttempt)
                + ",\"hosted_invoice_url\":\"https://invoice.stripe.com/i/" + id + "\",\"invoice_pdf\":"
                + "\"https://pay.stripe.com/invoice/" + id + "/pdf\",\"created\":" + now + "}";
    }

    private static String arr(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
    }
}
