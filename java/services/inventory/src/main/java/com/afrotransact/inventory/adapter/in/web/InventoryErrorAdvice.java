package com.afrotransact.inventory.adapter.in.web;

import java.net.URI;

import com.afrotransact.inventory.domain.InsufficientStockException;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.InventoryStateConflict;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps inventory domain errors to RFC 9457 problems with stable {@code inventory.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class InventoryErrorAdvice {

    private static final String TYPE_BASE = "https://developers.afrotransact.com/problems/";

    /** 409 with one entry per short SKU: {sku, nodeId, requested, available, missing}. */
    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficient(InsufficientStockException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, InsufficientStockException.CODE, "Insufficient stock",
                ex.getMessage());
        pd.setProperty("shortfalls", ex.shortfalls().stream().map(s -> new ShortfallView(s.sku(), s.nodeId(),
                s.requested(), s.available(), s.missing())).toList());
        return pd;
    }

    @ExceptionHandler(InventoryRuleViolation.class)
    ProblemDetail rule(InventoryRuleViolation ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Inventory rule violated",
                ex.getMessage());
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(InventoryStateConflict.class)
    ProblemDetail state(InventoryStateConflict ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), "Invalid state", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "inventory.invalid-input", "Invalid input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }

    /** Shortfall entry of an insufficient-stock problem. */
    record ShortfallView(String sku, String nodeId, int requested, int available, int missing) {
    }
}
