package com.afrotransact.bookings.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.port.GeoClientException;
import com.afrotransact.module.ModuleApiException;

/** The mapping {@code BookingsErrorAdvice} applies over HTTP, as the status a module API call fails with. */
final class BookingsFailures {

    private BookingsFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof BookingsRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, rule.code(), ex.getMessage(), rule.fieldErrors().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList(), ex));
        }
        if (ex instanceof BookingsStateConflict state) {
            return Optional.of(new ModuleApiException(409, state.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof GeoClientException) {
            return Optional.of(new ModuleApiException(502, "bookings.geo-unavailable",
                    "Searching by ZIP is temporarily unavailable; try again or search without a ZIP.", List.of(), ex));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new ModuleApiException(400, "bookings.invalid-input", ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }
}
