package com.afrotransact.deliveryruns.adapter.in.web;

import com.afrotransact.deliveryruns.domain.InvalidTransitionException;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps delivery-runs domain and port errors to RFC 9457 problems with stable {@code delivery-runs.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DeliveryRunsErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRunsErrorAdvice.class);

    @ExceptionHandler(RuleViolation.class)
    ProblemDetail rule(RuleViolation ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Delivery run rule violated", ex.getMessage());
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail transition(InvalidTransitionException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "delivery-runs.invalid-transition", "Invalid transition",
                ex.getMessage());
        pd.setProperty("from", ex.from());
        pd.setProperty("to", ex.to());
        return pd;
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    ProblemDetail concurrent(ConcurrentUpdateException ex) {
        return problem(HttpStatus.CONFLICT, "delivery-runs.concurrent-update", "Changed concurrently", ex.getMessage());
    }

    @ExceptionHandler(GeoUnavailableException.class)
    ProblemDetail geo(GeoUnavailableException ex) {
        LOG.warn("geo-service unavailable: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "delivery-runs.geo-unavailable", "Location service unavailable",
                "Distances and areas cannot be checked right now; try again shortly");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail mismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "delivery-runs.invalid-input", "Invalid request",
                "Invalid value for " + ex.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "delivery-runs.invalid-input", "Invalid request",
                "The request body could not be read");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(ProblemTypes.of(code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
