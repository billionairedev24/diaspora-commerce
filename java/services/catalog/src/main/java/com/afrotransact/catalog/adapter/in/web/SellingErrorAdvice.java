package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.application.SellingApprovalRequired;
import com.afrotransact.catalog.port.SellingApprovals.SellingUnavailable;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Selling approvals: a listing that cannot go live answers 409 {@code catalog.selling-approval-required} with a plain message,
 * what is missing and the seller-center path where the seller applies; seller-service being down answers 503 (fail closed).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SellingErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(SellingErrorAdvice.class);

    @ExceptionHandler(SellingApprovalRequired.class)
    ProblemDetail approvalRequired(SellingApprovalRequired ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(ProblemTypes.of(SellingApprovalRequired.CODE));
        pd.setTitle("Approval needed to sell this");
        pd.setProperty("code", SellingApprovalRequired.CODE);
        pd.setProperty("level", ex.level());
        pd.setProperty("missing", ex.missing());
        pd.setProperty("applyUrl", ex.applyUrl());
        return pd;
    }

    @ExceptionHandler(SellingUnavailable.class)
    ProblemDetail unavailable(SellingUnavailable ex) {
        LOG.warn("selling status unavailable: {}", ex.getMessage(), ex.getCause());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not check whether your store may sell this; try again in a minute. Your answers are not lost.");
        pd.setType(ProblemTypes.of("catalog.selling-unavailable"));
        pd.setTitle("Approvals unavailable");
        pd.setProperty("code", "catalog.selling-unavailable");
        return pd;
    }
}
