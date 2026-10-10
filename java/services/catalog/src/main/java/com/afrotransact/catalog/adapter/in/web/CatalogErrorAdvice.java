package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain rule violations to RFC 9457 problems with per-field errors for the product builder. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class CatalogErrorAdvice {

    @ExceptionHandler(CatalogRuleViolation.class)
    ProblemDetail ruleViolation(CatalogRuleViolation ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.rule-violation"));
        pd.setTitle("Catalog rule violated");
        pd.setProperty("code", "catalog.rule-violation");
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.invalid-transition"));
        pd.setProperty("code", "catalog.invalid-transition");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.invalid-input"));
        pd.setProperty("code", "catalog.invalid-input");
        return pd;
    }
}
