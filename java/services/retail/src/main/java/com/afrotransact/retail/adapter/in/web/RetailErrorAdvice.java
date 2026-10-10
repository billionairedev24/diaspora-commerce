package com.afrotransact.retail.adapter.in.web;

import java.net.URI;

import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.RetailStateConflict;
import com.afrotransact.retail.port.CatalogUnavailableException;
import com.afrotransact.retail.port.InventoryUnavailableException;
import com.afrotransact.retail.port.MediaUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps retail domain errors to RFC 9457 problems with stable {@code retail.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class RetailErrorAdvice {

    private static final String TYPE_BASE = "https://developers.afrotransact.com/problems/";

    @ExceptionHandler(RetailRuleViolation.class)
    ProblemDetail rule(RetailRuleViolation ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Purchasing rule violated",
                ex.getMessage());
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(RetailStateConflict.class)
    ProblemDetail state(RetailStateConflict ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), "Invalid state", ex.getMessage());
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    ProblemDetail inventoryDown(InventoryUnavailableException ex) {
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "retail.inventory-unavailable",
                "Inventory unavailable", ex.getMessage() + "; the receipt stays POSTING — retry completing it");
        pd.setProperty("retryable", true);
        return pd;
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    ProblemDetail catalogDown(CatalogUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "retail.catalog-unavailable", "Catalog unavailable",
                ex.getMessage());
    }

    @ExceptionHandler(MediaUnavailableException.class)
    ProblemDetail mediaDown(MediaUnavailableException ex) {
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "retail.media-unavailable", "Media unavailable",
                ex.getMessage());
        pd.setProperty("retryable", true);
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "retail.invalid-input", "Invalid input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
