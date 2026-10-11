package com.afrotransact.seller.adapter.out.stripe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.afrotransact.seller.domain.InvoiceLine;
import com.afrotransact.seller.domain.RequirementError;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.domain.StripePriceFacts;
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
        JsonNode price = item.path("price");
        return new SubscriptionSnapshot(text(s, "id"), idOf(s.path("customer")), text(s, "status"), text(item, "id"),
                idOf(price), price.isObject() ? text(price, "lookup_key") : null, epoch(s.path("trial_end")), start,
                end, s.path("cancel_at_period_end").asBoolean(false));
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
                epoch(i.path("next_payment_attempt")), epoch(i.path("created")), lines(i.path("lines").path("data")),
                i.path("ending_balance").isNumber() ? i.path("ending_balance").asLong() : null);
    }

    /**
     * Invoice lines. The price's lookup key is read from {@code price} (older API versions) or
     * {@code pricing.price_details} when Stripe expanded it; metered usage lines are recognised by the
     * {@code metered_*} lookup keys of the meters.
     */
    private static List<InvoiceLine> lines(JsonNode data) {
        List<InvoiceLine> out = new ArrayList<>();
        if (!data.isArray()) {
            return out;
        }
        for (JsonNode l : data) {
            String lookup = text(l.path("price"), "lookup_key");
            if (lookup == null) {
                lookup = text(l.path("pricing").path("price_details").path("price"), "lookup_key");
            }
            if (lookup == null) {
                lookup = text(l.path("metadata"), "metered_lookup_key");
            }
            String item = idOf(l.path("subscription_item"));
            if (item == null) {
                item = idOf(l.path("parent").path("subscription_item_details").path("subscription_item"));
            }
            JsonNode q = l.path("quantity");
            out.add(new InvoiceLine(text(l, "description"), l.path("amount").asLong(0),
                    q.isNumber() ? q.asLong() : null, lookup, item, InvoiceLine.meterOf(lookup),
                    epoch(l.path("period").path("start")), epoch(l.path("period").path("end"))));
        }
        return out;
    }

    /** A Price object (Stripe's JSON) as the facts the catalogue sync compares. */
    StripePriceFacts price(JsonNode p) {
        JsonNode r = p.path("recurring");
        String interval = r.path("interval").asString("month");
        int count = r.path("interval_count").asInt(1);
        int months = switch (interval) {
            case "year" -> 12 * count;
            case "month" -> count;
            default -> 0;
        };
        return new StripePriceFacts(text(p, "id"), idOf(p.path("product")), p.path("unit_amount").asLong(0),
                p.path("currency").asString("usd"), months, p.path("active").asBoolean(false), text(p, "lookup_key"));
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
