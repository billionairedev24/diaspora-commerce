package com.afrotransact.seller.adapter.out.stripe;

import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.StripeEvent;
import com.afrotransact.seller.port.StripeWebhookParser;
import com.afrotransact.seller.port.SubscriptionSnapshot;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies {@code Stripe-Signature} with stripe-java ({@link Webhook#constructEvent}: HMAC-SHA256 over
 * {@code t.payload}, 5-minute tolerance) against the endpoint's secret, then parses the event body.
 */
public class StripeWebhookAdapter implements StripeWebhookParser {

    private final String connectSecret;
    private final String billingSecret;
    private final StripeJson json;

    public StripeWebhookAdapter(String connectSecret, String billingSecret, JsonMapper mapper) {
        this.connectSecret = connectSecret;
        this.billingSecret = billingSecret;
        this.json = new StripeJson(mapper);
    }

    @Override
    public StripeEvent parse(Endpoint endpoint, String payload, String signatureHeader) {
        String secret = endpoint == Endpoint.CONNECT ? connectSecret : billingSecret;
        if (secret == null || secret.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            throw invalid();
        }
        try {
            Webhook.constructEvent(payload, signatureHeader, secret);
        } catch (SignatureVerificationException e) {
            throw invalid();
        }
        JsonNode e = json.tree(payload);
        JsonNode o = e.path("data").path("object");
        String type = StripeJson.text(e, "type");
        String account = StripeJson.text(e, "account");
        StripeEvent.Capability capability = null;
        StripeEvent.Person person = null;
        StripeEvent.ExternalAccount bank = null;
        StripeEvent.Payout payout = null;
        RequirementsSnapshot accountSnapshot = null;
        InvoiceSnapshot invoice = null;
        SubscriptionSnapshot subscription = null;
        StripeEvent.CreditNote creditNote = null;
        if (type != null) {
            switch (type) {
                case "account.updated" -> {
                    accountSnapshot = json.account(o).withPayoutBank(StripeAccountJson.payoutBank(o));
                    if (account == null) {
                        account = StripeJson.text(o, "id");
                    }
                }
                case "capability.updated" -> {
                    capability = new StripeEvent.Capability(StripeJson.text(o, "id"), StripeJson.text(o, "status"));
                    account = account == null ? StripeJson.idOf(o.path("account")) : account;
                }
                case "person.created", "person.updated" -> {
                    person = new StripeEvent.Person(StripeJson.text(o, "id"),
                            o.path("verification").path("status").asString(null),
                            o.path("relationship").path("representative").asBoolean(false));
                    account = account == null ? StripeJson.idOf(o.path("account")) : account;
                }
                case "account.external_account.created", "account.external_account.updated",
                     "account.external_account.deleted" -> {
                    if ("bank_account".equals(StripeJson.text(o, "object"))) {
                        bank = new StripeEvent.ExternalAccount(StripeJson.text(o, "id"),
                                StripeJson.text(o, "bank_name"), StripeJson.text(o, "last4"),
                                StripeJson.text(o, "status"));
                    }
                    account = account == null ? StripeJson.idOf(o.path("account")) : account;
                }
                case "payout.failed" -> payout = new StripeEvent.Payout(StripeJson.text(o, "id"),
                        o.path("amount").asLong(0), StripeJson.text(o, "currency"),
                        StripeJson.text(o, "failure_code"), StripeJson.text(o, "failure_message"),
                        StripeJson.epoch(o.path("arrival_date")));
                case "credit_note.created" -> creditNote = new StripeEvent.CreditNote(StripeJson.text(o, "id"),
                        StripeJson.idOf(o.path("invoice")), StripeJson.idOf(o.path("customer")),
                        o.path("total").asLong(0), o.path("out_of_band_amount").asLong(0));
                default -> {
                    if (type.startsWith("invoice.")) {
                        invoice = json.invoice(o);
                    } else if (type.startsWith("customer.subscription.")) {
                        subscription = json.subscription(o);
                    }
                }
            }
        }
        return new StripeEvent(StripeJson.text(e, "id"), type, e.path("created").asLong(0), account,
                accountSnapshot, capability, person, bank, payout, invoice, subscription, creditNote);
    }

    private static SellerRuleViolation invalid() {
        return new SellerRuleViolation("seller.invalid-signature", "Stripe signature verification failed");
    }
}
