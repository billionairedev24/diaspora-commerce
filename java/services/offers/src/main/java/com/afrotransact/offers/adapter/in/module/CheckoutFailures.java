package com.afrotransact.offers.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.offers.domain.CouponNotApplicable;
import com.afrotransact.offers.domain.OfferRuleViolation;

/** The mapping {@code OffersErrorAdvice} applies over HTTP to what checkout reads, as the status a module API call fails with. */
final class CheckoutFailures {

    private CheckoutFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof OfferRuleViolation) {
            return of(422, "offers.rule-violation", ex);
        }
        if (ex instanceof CouponNotApplicable) {
            return of(422, "offers.coupon-not-applicable", ex);
        }
        if (ex instanceof IllegalStateException) {
            return of(409, "offers.invalid-transition", ex);
        }
        if (ex instanceof IllegalArgumentException) {
            return of(400, "offers.invalid-input", ex);
        }
        return Optional.empty();
    }

    private static Optional<ModuleApiException> of(int status, String code, RuntimeException ex) {
        return Optional.of(new ModuleApiException(status, code, ex.getMessage(), List.of(), ex));
    }
}
