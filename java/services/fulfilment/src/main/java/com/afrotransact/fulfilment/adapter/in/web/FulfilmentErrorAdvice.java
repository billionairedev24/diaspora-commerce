package com.afrotransact.fulfilment.adapter.in.web;

import java.net.URI;

import com.afrotransact.fulfilment.application.DriverAccessDenied;
import com.afrotransact.fulfilment.domain.FulfilmentConflict;
import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps fulfilment errors to RFC 9457 problems with stable {@code fulfilment.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class FulfilmentErrorAdvice {

    private static final String TYPE_BASE = "https://developers.afrotransact.com/problems/";

    @ExceptionHandler(FulfilmentRuleViolation.class)
    ProblemDetail rule(FulfilmentRuleViolation ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), ex.getMessage());
    }

    @ExceptionHandler(FulfilmentConflict.class)
    ProblemDetail conflict(FulfilmentConflict ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrent(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "fulfilment.concurrent-update", "Changed by someone else; reload");
    }

    @ExceptionHandler(DriverAccessDenied.class)
    ProblemDetail driverAccess(DriverAccessDenied ex) {
        ProblemDetail pd = problem(HttpStatus.FORBIDDEN, ex.code(), ex.getMessage());
        pd.setProperty("signOut", ex.signOut());
        pd.setProperty("grantHint", DriverAccessDenied.GRANT_HINT);
        if (ex.revokedAt() != null) {
            pd.setProperty("revokedAt", ex.revokedAt().toString());
        }
        return pd;
    }

    @ExceptionHandler(UpstreamFailure.class)
    ProblemDetail upstream(UpstreamFailure ex) {
        HttpStatus status = ex.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        ProblemDetail pd = problem(status, "fulfilment.upstream-unavailable", ex.getMessage());
        pd.setProperty("upstream", ex.service());
        pd.setProperty("upstreamCode", ex.code());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "fulfilment.invalid-input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        return pd;
    }
}
