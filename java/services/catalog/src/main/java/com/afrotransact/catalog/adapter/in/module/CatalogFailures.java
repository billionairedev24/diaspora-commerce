package com.afrotransact.catalog.adapter.in.module;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.PreconditionRequiredException;
import com.afrotransact.catalog.domain.ReviewStateConflict;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.port.SellerOffers.OffersUnavailable;
import com.afrotransact.catalog.port.SellerScopes.ScopeUnavailable;
import com.afrotransact.catalog.port.TaxResolver.TaxProfileNotFound;
import com.afrotransact.catalog.port.TaxResolver.TaxServiceUnavailable;
import com.afrotransact.module.ModuleApiException;

/** Catalog's own error types as the status and code {@code CatalogErrorAdvice} gives them over HTTP (the ones the moderation paths raise). */
final class CatalogFailures {

    private CatalogFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof TaxNotReady notReady) {
            return Optional.of(new ModuleApiException(422, "catalog.tax-not-ready", notReady.getMessage(),
                    lines(notReady.fieldErrors()), ex));
        }
        if (ex instanceof CatalogRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, "catalog.rule-violation", rule.getMessage(), lines(rule.fieldErrors()), ex));
        }
        if (ex instanceof TaxProfileNotFound) {
            return Optional.of(new ModuleApiException(422, "catalog.tax-profile-missing",
                    "This category cannot be published into yet: it has no tax rules", List.of("category: This category has no tax rules yet; please try later"), ex));
        }
        if (ex instanceof TaxServiceUnavailable) {
            return Optional.of(new ModuleApiException(503, "catalog.tax-unavailable",
                    "We could not check the tax details of this product right now; try again in a minute.", List.of(), ex));
        }
        if (ex instanceof OffersUnavailable) {
            return Optional.of(new ModuleApiException(503, "catalog.offers-unavailable",
                    "We could not check your offers right now; try again in a minute", List.of(), ex));
        }
        if (ex instanceof ScopeUnavailable) {
            return Optional.of(new ModuleApiException(503, "catalog.scope-unavailable",
                    "We could not check which departments your store sells in; try again in a minute", List.of(), ex));
        }
        if (ex instanceof ReviewStateConflict conflict) {
            return Optional.of(new ModuleApiException(409, conflict.code(), conflict.getMessage(), List.of(), ex));
        }
        if (ex instanceof StaleVersionException) {
            return Optional.of(new ModuleApiException(412, "catalog.version-mismatch", ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof PreconditionRequiredException) {
            return Optional.of(new ModuleApiException(428, "catalog.if-match-required", ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof IllegalStateException) {
            return Optional.of(new ModuleApiException(409, "catalog.invalid-transition", ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new ModuleApiException(400, "catalog.invalid-input", ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }

    private static List<String> lines(Map<String, String> fieldErrors) {
        return fieldErrors == null ? List.of() : fieldErrors.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
    }
}
