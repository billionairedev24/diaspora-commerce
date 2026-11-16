package com.afrotransact.deliveryruns.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.deliveryruns.application.VersionMismatchException;
import com.afrotransact.deliveryruns.domain.InvalidTransitionException;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.afrotransact.deliveryruns.port.UpstreamRejectedException;
import com.afrotransact.deliveryruns.port.UpstreamUnavailableException;
import com.afrotransact.module.ModuleApiException;

/** delivery-runs' own error types as the status and code {@code DeliveryRunsErrorAdvice} gives them over HTTP. */
final class DeliveryRunsFailures {

    private DeliveryRunsFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof RuleViolation rule) {
            return Optional.of(new ModuleApiException(422, rule.code(), rule.getMessage(), List.of(), ex));
        }
        if (ex instanceof VersionMismatchException) {
            return Optional.of(new ModuleApiException(412, "delivery-runs.version-mismatch", ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof UpstreamUnavailableException up) {
            return Optional.of(new ModuleApiException(502, "delivery-runs." + up.service() + "-unavailable",
                    "The " + up.service() + " service cannot answer right now; try again shortly", List.of(), ex));
        }
        if (ex instanceof UpstreamRejectedException rejected) {
            return Optional.of(new ModuleApiException(rejected.upstreamStatus() == 409 ? 409 : 422, rejected.code(),
                    rejected.getMessage(), List.of(), ex));
        }
        if (ex instanceof DuplicateEntryException) {
            return Optional.of(new ModuleApiException(409, "delivery-runs.duplicate", ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof InvalidTransitionException) {
            return Optional.of(new ModuleApiException(409, "delivery-runs.invalid-transition", ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof ConcurrentUpdateException) {
            return Optional.of(new ModuleApiException(409, "delivery-runs.concurrent-update", ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof GeoUnavailableException) {
            return Optional.of(new ModuleApiException(502, "delivery-runs.geo-unavailable",
                    "Distances and areas cannot be checked right now; try again shortly", List.of(), ex));
        }
        return Optional.empty();
    }
}
