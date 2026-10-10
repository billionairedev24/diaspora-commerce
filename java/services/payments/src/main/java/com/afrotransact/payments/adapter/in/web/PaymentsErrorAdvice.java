package com.afrotransact.payments.adapter.in.web;

import java.net.URI;

import com.afrotransact.payments.domain.PaymentsForbidden;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.port.StripeGatewayException;
import com.afrotransact.payments.port.StripeWebhookVerifier.InvalidWebhookSignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps payments domain errors to RFC 9457 problems with stable {@code payments.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PaymentsErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentsErrorAdvice.class);
    private static final String TYPE_BASE = "https://developers.afrotransact.com/problems/";

    @ExceptionHandler(PaymentsRuleViolation.class)
    ProblemDetail rule(PaymentsRuleViolation ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Payments rule violated",
                ex.getMessage());
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(PaymentsStateConflict.class)
    ProblemDetail state(PaymentsStateConflict ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), "Invalid state", ex.getMessage());
    }

    @ExceptionHandler(PaymentsForbidden.class)
    ProblemDetail forbidden(PaymentsForbidden ex) {
        return problem(HttpStatus.FORBIDDEN, ex.code(), "Not allowed", ex.getMessage());
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    ProblemDetail signature(InvalidWebhookSignatureException ex) {
        LOG.warn("Rejected Stripe webhook: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "payments.invalid-signature", "Invalid webhook signature",
                "The Stripe-Signature header does not match this endpoint's secret");
    }

    /** Card declines are 402; other Stripe refusals 422; Stripe outages 502 (safe to retry: idempotency keys). */
    @ExceptionHandler(StripeGatewayException.class)
    ProblemDetail stripe(StripeGatewayException ex) {
        ProblemDetail pd;
        if ("card_declined".equals(ex.stripeCode()) || ex.declineCode() != null) {
            pd = problem(HttpStatus.PAYMENT_REQUIRED, "payments.card-declined", "Card declined", ex.getMessage());
        } else if (ex.clientError()) {
            pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, "payments.stripe-rejected", "Stripe refused the request",
                    ex.getMessage());
        } else {
            LOG.error("Stripe call failed", ex);
            pd = problem(HttpStatus.BAD_GATEWAY, "payments.stripe-unavailable", "Stripe unavailable",
                    "Stripe could not be reached; retry with the same Idempotency-Key");
        }
        pd.setProperty("stripeCode", ex.stripeCode());
        pd.setProperty("declineCode", ex.declineCode());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "payments.invalid-input", "Invalid input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
