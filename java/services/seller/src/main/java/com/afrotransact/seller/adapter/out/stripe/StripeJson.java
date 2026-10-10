package com.afrotransact.seller.adapter.out.stripe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.afrotransact.seller.domain.RequirementError;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.SubscriptionSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads Stripe JSON (API responses and webhook {@code data.object}) into port records. Only verification results are
 * read from accounts: requirement keys, flags and statuses — never ID images or tax numbers.
 */
final class StripeJson {

    private final JsonMapper mapper;

    StripeJson(JsonMapper mapper) {
        this.mapper = mapper;
    }

    JsonNode tree(String json) {
        return mapper.readTree(json);
    }

    RequirementsSnapshot account(JsonNode a) {
        JsonNode r = a.path("requirements");
        List<RequirementError> errors = new ArrayList<>();
        for (JsonNode e : r.path("errors")) {
            errors.add(new RequirementError(text(e, "requirement"), text(e, "code"), text(e, "reason")));
        }
        Map<String, String> caps = new TreeMap<>();
        a.path("capabilities").properties().forEach(en -> caps.put(en.getKey(), en.getValue().isString()
                ? en.getValue().asString() : en.getValue().path("status").asString("")));
        String individual = a.path("individual").path("verification").path("status").asString(null);
        return new RequirementsSnapshot(strings(r.path("currently_due")), strings(r.path("eventually_due")),
                strings(r.path("past_due")), strings(r.path("pending_verification")), errors,
                epoch(r.path("current_deadline")), text(r, "disabled_reason"),
                a.path("charges_enabled").asBoolean(false), a.path("payouts_enabled").asBoolean(false),
                a.path("details_submitted").asBoolean(false), individual, caps);
    }

    SubscriptionSnapshot subscription(JsonNode s) {
        JsonNode item = s.path("items").path("data").path(0);
        Instant start = epoch(s.path("current_period_start"));
        Instant end = epoch(s.path("current_period_end"));
        if (start == null) {
            start = epoch(item.path("current_period_start"));
        }
        if (end == null) {
            end = epoch(item.path("current_period_end"));
        }
        return new SubscriptionSnapshot(text(s, "id"), idOf(s.path("customer")), text(s, "status"), text(item, "id"),
                item.path("price").path("lookup_key").asString(null), epoch(s.path("trial_end")), start, end,
                s.path("cancel_at_period_end").asBoolean(false));
    }

    InvoiceSnapshot invoice(JsonNode i) {
        String sub = idOf(i.path("subscription"));
        if (sub == null) {
            sub = idOf(i.path("parent").path("subscription_details").path("subscription"));
        }
        return new InvoiceSnapshot(text(i, "id"), idOf(i.path("customer")), sub, text(i, "number"), text(i, "status"),
                text(i, "billing_reason"), i.path("amount_due").asLong(0), i.path("amount_paid").asLong(0),
                i.path("currency").asString("usd"), epoch(i.path("period_start")), epoch(i.path("period_end")),
                text(i, "hosted_invoice_url"), text(i, "invoice_pdf"), i.path("attempt_count").asInt(0),
                epoch(i.path("next_payment_attempt")), epoch(i.path("created")));
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    static Instant epoch(JsonNode n) {
        return n.isNumber() ? Instant.ofEpochSecond(n.asLong()) : null;
    }

    /** An expandable field: either the id string or an object with an id. */
    static String idOf(JsonNode n) {
        if (n.isString()) {
            return n.asString();
        }
        return n.isObject() ? text(n, "id") : null;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(v -> out.add(v.asString()));
        return out;
    }
}
