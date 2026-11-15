package com.afrotransact.orders.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;

/** The mapping {@code OrdersErrorAdvice} applies over HTTP to the lookups, as the status a module API call fails with. */
final class OrdersFailures {

    private OrdersFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof OrdersRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, rule.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof OrdersStateConflict state) {
            return Optional.of(new ModuleApiException(409, state.code(), ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }
}
