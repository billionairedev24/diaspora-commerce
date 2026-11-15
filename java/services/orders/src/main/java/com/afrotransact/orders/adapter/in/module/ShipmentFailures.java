package com.afrotransact.orders.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.domain.OrdersRateLimited;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.TransitionRefused;
import org.springframework.dao.OptimisticLockingFailureException;

/** The mapping {@code OrdersErrorAdvice} applies over HTTP to shipment calls, as the status a module API call fails with. */
final class ShipmentFailures {

    private ShipmentFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof OrdersRuleViolation rule) {
            return of(422, rule.code(), ex);
        }
        if (ex instanceof OrdersRateLimited limited) {
            return of(429, limited.code(), ex);
        }
        if (ex instanceof OptimisticLockingFailureException) {
            return of(409, "orders.concurrent-update", ex);
        }
        if (ex instanceof OrdersStateConflict state) {
            String code = state.code() == null ? "" : state.code();
            int status = "orders.checkout-unavailable".equals(code) ? 503 : "orders.pay-link-expired".equals(code) ? 410 : 409;
            return of(status, state.code(), ex);
        }
        if (ex instanceof TransitionRefused refused) {
            return of(refused.forbidden() ? 403 : 409, refused.code(), ex);
        }
        if (ex instanceof IllegalArgumentException) {
            return of(400, "orders.invalid-input", ex);
        }
        return Optional.empty();
    }

    private static Optional<ModuleApiException> of(int status, String code, RuntimeException ex) {
        return Optional.of(new ModuleApiException(status, code, ex.getMessage(), List.of(), ex));
    }
}
