package com.afrotransact.content.adapter.in.web;

import java.net.URI;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.ContentStateConflict;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps content domain errors to RFC 9457 problems with stable {@code content.*} codes and per-field errors. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ContentErrorAdvice {

    private static final String TYPE_BASE = "https://developers.afrotransact.com/problems/";

    @ExceptionHandler(ContentRuleViolation.class)
    ProblemDetail rule(ContentRuleViolation ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Content rule violated", ex.getMessage());
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(ContentStateConflict.class)
    ProblemDetail state(ContentStateConflict ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), "Invalid state", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "content.invalid-input", "Invalid input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
