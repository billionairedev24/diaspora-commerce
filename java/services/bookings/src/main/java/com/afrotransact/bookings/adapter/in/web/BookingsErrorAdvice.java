package com.afrotransact.bookings.adapter.in.web;

import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.port.GeoClientException;
import com.afrotransact.bookings.port.PaymentsClientException;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps bookings' domain and payments errors to RFC 9457 problems with stable {@code bookings.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class BookingsErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(BookingsErrorAdvice.class);

    @ExceptionHandler(BookingsRuleViolation.class)
    ProblemDetail ruleViolation(BookingsRuleViolation ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Booking rule violated",
                ex.getMessage());
        if (!ex.fieldErrors().isEmpty()) {
            pd.setProperty("fieldErrors", ex.fieldErrors());
        }
        return pd;
    }

    @ExceptionHandler(BookingsStateConflict.class)
    ProblemDetail conflict(BookingsStateConflict ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), "Invalid booking state", ex.getMessage());
    }

    @ExceptionHandler(PaymentsClientException.class)
    ProblemDetail payments(PaymentsClientException ex) {
        if (ex.rejected()) {
            ProblemDetail pd = problem(HttpStatus.CONFLICT, "bookings.payment-rejected", "Payment refused",
                    ex.getMessage());
            pd.setProperty("paymentsCode", ex.code());
            return pd;
        }
        LOG.warn("payments-service unavailable: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "bookings.payments-unavailable", "Payments unavailable",
                "Payments are temporarily unavailable; nothing was changed. Retry with the same Idempotency-Key.");
    }

    @ExceptionHandler(GeoClientException.class)
    ProblemDetail geo(GeoClientException ex) {
        LOG.warn("geo-service unavailable: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "bookings.geo-unavailable", "Location search unavailable",
                "Searching by ZIP is temporarily unavailable; try again or search without a ZIP.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "bookings.invalid-input", "Invalid input", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(ProblemTypes.of(code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
