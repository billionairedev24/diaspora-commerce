package com.afrotransact.deliveryruns.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.deliveryruns.application.VersionMismatchException;
import com.afrotransact.deliveryruns.domain.InvalidTransitionException;
import com.afrotransact.deliveryruns.domain.PublishBlockedException;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.afrotransact.deliveryruns.port.UpstreamRejectedException;
import com.afrotransact.deliveryruns.port.UpstreamUnavailableException;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps delivery-runs domain and port errors to RFC 9457 problems with stable {@code delivery-runs.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DeliveryRunsErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRunsErrorAdvice.class);

    /** Publish-check codes → the back office's check keys (field errors of 422 preorder-checks-failed). */
    private static final Map<String, String> CHECK_KEYS = Map.of(
            "delivery-runs.preorder-name-required", "name",
            "delivery-runs.preorder-area-required", "area",
            "delivery-runs.preorder-closes-before-opens", "dates",
            "delivery-runs.preorder-delivers-before-close", "delivery",
            "delivery-runs.preorder-hero-required", "hero",
            "delivery-runs.preorder-ribbon-required", "ribbon",
            "delivery-runs.preorder-fulfilment-required", "fulfilment",
            "delivery-runs.preorder-fee-missing", "doorFee");

    @ExceptionHandler(RuleViolation.class)
    ProblemDetail rule(RuleViolation ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Delivery run rule violated", ex.getMessage());
    }

    @ExceptionHandler(PublishBlockedException.class)
    ProblemDetail publishBlocked(PublishBlockedException ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Pre-order checks failed",
                ex.getMessage());
        pd.setProperty("violations", ex.violations().stream()
                .map(v -> Map.of("code", v.code(), "message", v.getMessage())).toList());
        Map<String, String> errors = new LinkedHashMap<>();
        ex.violations().forEach(v -> errors.put(CHECK_KEYS.getOrDefault(v.code(), v.code()), v.getMessage()));
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(VersionMismatchException.class)
    ProblemDetail versionMismatch(VersionMismatchException ex) {
        ProblemDetail pd = problem(HttpStatus.PRECONDITION_FAILED, "delivery-runs.version-mismatch",
                "Changed by someone else", ex.getMessage());
        pd.setProperty("currentVersion", ex.current());
        return pd;
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ProblemDetail upstream(UpstreamUnavailableException ex) {
        LOG.warn("{}-service unavailable: {}", ex.service(), ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "delivery-runs." + ex.service() + "-unavailable",
                "Upstream service unavailable", "The " + ex.service() + " service cannot answer right now; try again "
                        + "shortly");
    }

    @ExceptionHandler(UpstreamRejectedException.class)
    ProblemDetail upstreamRejected(UpstreamRejectedException ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Refused by orders-service",
                ex.getMessage());
        pd.setProperty("upstreamStatus", ex.upstreamStatus());
        return pd;
    }

    @ExceptionHandler(DuplicateEntryException.class)
    ProblemDetail duplicate(DuplicateEntryException ex) {
        return problem(HttpStatus.CONFLICT, "delivery-runs.duplicate", "Already exists", ex.getMessage());
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail transition(InvalidTransitionException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "delivery-runs.invalid-transition", "Invalid transition",
                ex.getMessage());
        pd.setProperty("from", ex.from());
        pd.setProperty("to", ex.to());
        return pd;
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    ProblemDetail concurrent(ConcurrentUpdateException ex) {
        return problem(HttpStatus.CONFLICT, "delivery-runs.concurrent-update", "Changed concurrently", ex.getMessage());
    }

    @ExceptionHandler(GeoUnavailableException.class)
    ProblemDetail geo(GeoUnavailableException ex) {
        LOG.warn("geo-service unavailable: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "delivery-runs.geo-unavailable", "Location service unavailable",
                "Distances and areas cannot be checked right now; try again shortly");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail mismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "delivery-runs.invalid-input", "Invalid request",
                "Invalid value for " + ex.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "delivery-runs.invalid-input", "Invalid request",
                "The request body could not be read");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(ProblemTypes.of(code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
