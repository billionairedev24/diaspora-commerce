package com.afrotransact.catalog.adapter.in.web;

import java.util.Map;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain rule violations to RFC 9457 problems with per-field errors for the product form and bulk upload. */
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

    @ExceptionHandler(SpreadsheetRejected.class)
    ProblemDetail uploadRejected(SpreadsheetRejected ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.upload-rejected"));
        pd.setTitle("The file cannot be imported");
        pd.setProperty("code", "catalog.upload-rejected");
        pd.setProperty("fieldErrors", Map.of("file", ex.getMessage()));
        return pd;
    }

    @ExceptionHandler(ImageServiceUnavailable.class)
    ProblemDetail imageServiceUnavailable(ImageServiceUnavailable ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The image library is unavailable; try again in a minute");
        pd.setType(ProblemTypes.of("catalog.media-unavailable"));
        pd.setTitle("Image library unavailable");
        pd.setProperty("code", "catalog.media-unavailable");
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
