package com.afrotransact.inventory.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.inventory.application.InventoryUpstreamUnavailable;
import com.afrotransact.inventory.domain.InsufficientStockException;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.InventoryStateConflict;
import com.afrotransact.module.ModuleApiException;

/** The mapping {@code InventoryErrorAdvice} applies over HTTP, as the status a module API call fails with. */
final class InventoryFailures {

    private InventoryFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof InsufficientStockException stock) {
            List<String> details = stock.shortfalls().stream().map(s -> s.sku() + ": requested " + s.requested()
                    + ", available " + s.available()).toList();
            return Optional.of(new ModuleApiException(409, InsufficientStockException.CODE, ex.getMessage(), details, ex));
        }
        if (ex instanceof InventoryRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, rule.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof InventoryStateConflict state) {
            return Optional.of(new ModuleApiException(409, state.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof InventoryUpstreamUnavailable down) {
            return Optional.of(new ModuleApiException(503, down.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new ModuleApiException(400, "inventory.invalid-input", ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }
}
