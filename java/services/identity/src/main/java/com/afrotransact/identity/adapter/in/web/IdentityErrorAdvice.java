package com.afrotransact.identity.adapter.in.web;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.SignInFailed;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps identity rule violations (422 with field errors), failed sign-in steps (401), rate limits and sign-in lockouts
 * (429 + Retry-After) and lost updates (409).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdentityErrorAdvice {

    @ExceptionHandler(IdentityRuleViolation.class)
    ProblemDetail ruleViolation(IdentityRuleViolation ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setType(ProblemTypes.of(ex.code()));
        pd.setTitle("Identity rule violated");
        pd.setProperty("code", ex.code());
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(RateLimitExceeded.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitExceeded ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        pd.setType(ProblemTypes.of(ex.code()));
        pd.setTitle("Too many requests");
        pd.setProperty("code", ex.code());
        long seconds = Math.max(1, ex.retryAfter().toSeconds());
        pd.setProperty("retryAfterSeconds", seconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(pd);
    }

    @ExceptionHandler(SignInFailed.class)
    ProblemDetail signInFailed(SignInFailed ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setType(ProblemTypes.of(ex.code()));
        pd.setTitle("Sign-in failed");
        pd.setProperty("code", ex.code());
        if (ex.attemptsLeft() != null) {
            pd.setProperty("attemptsLeft", ex.attemptsLeft());
        }
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(ProblemTypes.of("identity.invalid-input"));
        pd.setProperty("code", "identity.invalid-input");
        return pd;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail conflict(OptimisticLockingFailureException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The record was changed by someone else; reload and try again");
        pd.setType(ProblemTypes.of("identity.concurrent-update"));
        pd.setProperty("code", "identity.concurrent-update");
        return pd;
    }
}
