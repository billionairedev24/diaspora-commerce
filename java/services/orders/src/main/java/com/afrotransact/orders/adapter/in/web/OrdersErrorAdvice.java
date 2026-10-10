package com.afrotransact.orders.adapter.in.web;

import com.afrotransact.orders.application.CheckoutCodes;
import com.afrotransact.orders.application.CheckoutFailed;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.TransitionRefused;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps orders errors to RFC 9457 problems with stable {@code orders.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class OrdersErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(OrdersErrorAdvice.class);

    @ExceptionHandler(OrdersRuleViolation.class)
    ProblemDetail rule(OrdersRuleViolation ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Orders rule violated", ex.getMessage());
    }

    @ExceptionHandler(OrdersStateConflict.class)
    ProblemDetail state(OrdersStateConflict ex) {
        HttpStatus status = "orders.checkout-unavailable".equals(ex.code()) ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CONFLICT;
        return problem(status, ex.code(), "Invalid state", ex.getMessage());
    }

    @ExceptionHandler(TransitionRefused.class)
    ProblemDetail transition(TransitionRefused ex) {
        return problem(ex.forbidden() ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT, ex.code(),
                ex.forbidden() ? "Not your step" : "Invalid transition", ex.getMessage());
    }

    @ExceptionHandler(CheckoutFailed.class)
    ProblemDetail checkout(CheckoutFailed ex) {
        HttpStatus status = switch (ex.code() == null ? "" : ex.code()) {
            case CheckoutCodes.INSUFFICIENT_STOCK, CheckoutCodes.RESERVATION_EXPIRED -> HttpStatus.CONFLICT;
            case CheckoutCodes.PAYMENT_FAILED, CheckoutCodes.PAYMENT_TIMEOUT -> HttpStatus.PAYMENT_REQUIRED;
            case CheckoutCodes.UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CheckoutCodes.UPSTREAM_REJECTED -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        ProblemDetail pd = problem(status, ex.code() == null ? "orders.checkout-failed" : ex.code(),
                "Checkout failed", ex.getMessage());
        pd.setProperty("orderNumber", ex.orderNumber());
        return pd;
    }

    @ExceptionHandler(UpstreamFailure.class)
    ProblemDetail upstream(UpstreamFailure ex) {
        LOG.warn("Upstream {} failed: {} {}", ex.service(), ex.code(), ex.getMessage());
        HttpStatus status = ex.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        ProblemDetail pd = problem(status, "orders.upstream-" + (ex.retryable() ? "unavailable" : "rejected"),
                "Upstream " + ex.service() + " failed", ex.getMessage());
        pd.setProperty("upstreamCode", ex.code());
        return pd;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail header(MissingRequestHeaderException ex) {
        String code = CheckoutController.IDEMPOTENCY_KEY.equalsIgnoreCase(ex.getHeaderName())
                ? "orders.idempotency-key-required" : "orders.header-required";
        return problem(HttpStatus.BAD_REQUEST, code, "Missing header", "Header " + ex.getHeaderName()
                + " is required");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "orders.invalid-input", "Invalid input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(ProblemTypes.of(code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
