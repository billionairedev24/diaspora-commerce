package com.afrotransact.seller.adapter.in.web;

import com.afrotransact.platform.web.error.ProblemTypes;
import com.afrotransact.seller.domain.ApplicationAlreadyDecided;
import com.afrotransact.seller.domain.SellerForbidden;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.GeoUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps seller domain errors to RFC 9457 problems with stable {@code seller.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SellerErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(SellerErrorAdvice.class);

    @ExceptionHandler(SellerRuleViolation.class)
    ProblemDetail rule(SellerRuleViolation ex) {
        HttpStatus status = "seller.invalid-signature".equals(ex.code()) ? HttpStatus.BAD_REQUEST
                : HttpStatus.UNPROCESSABLE_CONTENT;
        ProblemDetail pd = problem(status, ex.code(), "Seller rule violated", ex.getMessage());
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(SellerStateConflict.class)
    ProblemDetail state(SellerStateConflict ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), "Invalid state", ex.getMessage());
    }

    /** Already decided: says what, by whom and when, so the console can show the current state as information. */
    @ExceptionHandler(ApplicationAlreadyDecided.class)
    ProblemDetail decided(ApplicationAlreadyDecided ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, ex.code(), "Already decided", ex.getMessage());
        pd.setProperty("decision", ex.status().name());
        pd.setProperty("decidedBy", ex.deciderLabel());
        pd.setProperty("decidedAt", ex.decidedAt());
        return pd;
    }

    @ExceptionHandler(SellerForbidden.class)
    ProblemDetail forbidden(SellerForbidden ex) {
        return problem(HttpStatus.FORBIDDEN, ex.code(), "Not allowed", ex.getMessage());
    }

    @ExceptionHandler(GatewayException.class)
    ProblemDetail gateway(GatewayException ex) {
        if (!ex.rejected()) {
            LOG.warn("External call failed: {}", ex.getMessage(), ex);
        }
        return problem(ex.rejected() ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.BAD_GATEWAY, ex.code(),
                ex.rejected() ? "Rejected by Stripe" : "Upstream unavailable", ex.getMessage());
    }

    @ExceptionHandler(GeoUnavailableException.class)
    ProblemDetail geo(GeoUnavailableException ex) {
        LOG.warn("geo-service unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "seller.geo-unavailable", "Upstream unavailable",
                "Address lookup is temporarily unavailable; try again shortly");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "seller.invalid-input", "Invalid input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(ProblemTypes.of(code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
