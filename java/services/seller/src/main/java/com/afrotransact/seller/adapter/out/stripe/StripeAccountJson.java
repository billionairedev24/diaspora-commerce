package com.afrotransact.seller.adapter.out.stripe;

import com.afrotransact.seller.domain.PayoutBank;
import tools.jackson.databind.JsonNode;

/**
 * The payout bank account in a Stripe Account object ({@code external_accounts}, listed for the platform that controls
 * the account) or in an {@code account.external_account.*} event: id, bank name, last 4 and status, never the numbers.
 */
final class StripeAccountJson {

    private StripeAccountJson() {
    }

    /** The default bank account for the account's currency, else the first bank account; null when none is listed. */
    static PayoutBank payoutBank(JsonNode account) {
        JsonNode chosen = null;
        for (JsonNode x : account.path("external_accounts").path("data")) {
            if (!"bank_account".equals(StripeJson.text(x, "object"))) {
                continue;
            }
            if (chosen == null || x.path("default_for_currency").asBoolean(false)) {
                chosen = x;
            }
        }
        return chosen == null ? null : bank(chosen);
    }

    /** One external account object (a bank account). */
    static PayoutBank bank(JsonNode x) {
        return new PayoutBank(StripeJson.text(x, "id"), StripeJson.text(x, "bank_name"), StripeJson.text(x, "last4"),
                StripeJson.text(x, "status"));
    }
}
