package com.afrotransact.trustops.adapter.in.web;

import java.net.URI;

import com.afrotransact.trustops.domain.TrustRuleViolation;
import com.afrotransact.trustops.port.UpstreamException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps trust-ops rule violations, invalid transitions and upstream failures to RFC 9457 problems. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class TrustOpsErrorAdvice {

    private static final String TYPE_BASE = "https://developers.afrotransact.com/problems/";

    @ExceptionHandler(TrustRuleViolation.class)
    ProblemDetail ruleViolation(TrustRuleViolation ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), ex.getMessage());
        pd.setTitle("Trust & safety rule violated");
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException ex) {
        return problem(HttpStatus.CONFLICT, "trustops.invalid-transition", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "trustops.invalid-input", ex.getMessage());
    }

    @ExceptionHandler(UpstreamException.class)
    ProblemDetail upstream(UpstreamException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "trustops.upstream-unavailable", ex.getMessage());
        pd.setProperty("service", ex.service());
        pd.setProperty("upstreamStatus", ex.status());
        pd.setProperty("upstreamCode", ex.code());
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setProperty("code", code);
        return pd;
    }
}
