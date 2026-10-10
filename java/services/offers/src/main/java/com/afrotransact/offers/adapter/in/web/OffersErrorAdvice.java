package com.afrotransact.offers.adapter.in.web;

import com.afrotransact.offers.domain.CouponNotApplicable;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.port.CatalogClient.CatalogUnavailableException;
import com.afrotransact.offers.port.PickupLocationClient.SellerUnavailableException;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps offers rule violations to RFC 9457 problems with stable {@code offers.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class OffersErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(OffersErrorAdvice.class);

    @ExceptionHandler(OfferRuleViolation.class)
    ProblemDetail ruleViolation(OfferRuleViolation ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, "offers.rule-violation", ex.getMessage());
        pd.setTitle("Offers rule violated");
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(CouponNotApplicable.class)
    ProblemDetail couponNotApplicable(CouponNotApplicable ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, "offers.coupon-not-applicable",
                ex.getMessage());
        pd.setProperty("reason", ex.rejection().name());
        return pd;
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    ProblemDetail catalogUnavailable(CatalogUnavailableException ex) {
        LOG.warn("catalog-service unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "offers.catalog-unavailable",
                "The catalog could not be reached; retry shortly");
    }

    @ExceptionHandler(SellerUnavailableException.class)
    ProblemDetail sellerUnavailable(SellerUnavailableException ex) {
        LOG.warn("seller-service unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "offers.seller-unavailable",
                "The seller's pickup location could not be checked; retry shortly");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException ex) {
        return problem(HttpStatus.CONFLICT, "offers.invalid-transition", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "offers.invalid-input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(ProblemTypes.of(code));
        pd.setProperty("code", code);
        return pd;
    }
}
