package com.afrotransact.payments.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.payments.domain.PaymentsForbidden;
import com.afrotransact.payments.domain.PaymentsRateLimited;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.port.OrgMembershipUnavailableException;
import com.afrotransact.payments.port.StripeGatewayException;

/** The mapping {@code PaymentsErrorAdvice} applies over HTTP, as the status a module API call fails with. */
final class PaymentsFailures {

    private PaymentsFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof PaymentsRuleViolation rule) {
            return of(422, rule.code(), ex);
        }
        if (ex instanceof PaymentsStateConflict state) {
            return of(409, state.code(), ex);
        }
        if (ex instanceof PaymentsForbidden forbidden) {
            return of(403, forbidden.code(), ex);
        }
        if (ex instanceof PaymentsRateLimited limited) {
            return of(429, limited.code(), ex);
        }
        if (ex instanceof StripeGatewayException stripe) {
            if ("card_declined".equals(stripe.stripeCode()) || stripe.declineCode() != null) {
                return of(402, "payments.card-declined", ex);
            }
            return stripe.clientError() ? of(422, "payments.stripe-rejected", ex)
                    : Optional.of(new ModuleApiException(502, "payments.stripe-unavailable",
                    "Stripe could not be reached; retry with the same Idempotency-Key", List.of(), ex));
        }
        if (ex instanceof OrgMembershipUnavailableException) {
            return of(503, "payments.membership-unavailable", ex);
        }
        if (ex instanceof IllegalArgumentException) {
            return of(400, "payments.invalid-input", ex);
        }
        return Optional.empty();
    }

    private static Optional<ModuleApiException> of(int status, String code, RuntimeException ex) {
        return Optional.of(new ModuleApiException(status, code, ex.getMessage(), List.of(), ex));
    }
}
