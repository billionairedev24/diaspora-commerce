package com.afrotransact.taxreturns.adapter.in.web;

import java.net.URI;

import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps return / tax rule violations, invalid transitions and upstream failures to RFC 9457 problems. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class TaxReturnsErrorAdvice {

    private static final String TYPE_BASE = "https://developers.afrotransact.com/problems/";

    @ExceptionHandler(ReturnRuleViolation.class)
    ProblemDetail ruleViolation(ReturnRuleViolation ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), ex.getMessage());
        pd.setTitle("Return or tax rule violated");
        if (!ex.fieldErrors().isEmpty()) {
            pd.setProperty("fieldErrors", ex.fieldErrors());
        }
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException ex) {
        return problem(HttpStatus.CONFLICT, "returns.invalid-transition", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "tax-returns.invalid-input", ex.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException ex) {
        return problem(HttpStatus.BAD_REQUEST, "tax-returns.missing-header", "Missing header " + ex.getHeaderName());
    }

    @ExceptionHandler(UpstreamException.class)
    ProblemDetail upstream(UpstreamException ex) {
        HttpStatus status = ex.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        ProblemDetail pd = problem(status, "tax-returns.upstream-unavailable", ex.getMessage());
        pd.setProperty("upstream", ex.upstream());
        if (ex.code() != null) {
            pd.setProperty("upstreamCode", ex.code());
        }
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setProperty("code", code);
        return pd;
    }
}
