package com.afrotransact.taxreturns.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.port.UpstreamException;
import com.afrotransact.taxreturns.port.VariantClassificationRepository.StaleRecordException;

/** The mapping {@code TaxReturnsErrorAdvice} applies over HTTP, as the status a module API call fails with. */
final class TaxReturnsFailures {

    private TaxReturnsFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof ReturnRuleViolation rule) {
            return of(422, rule.code(), ex);
        }
        if (ex instanceof StaleRecordException) {
            return of(409, "tax.classification-changed", ex);
        }
        if (ex instanceof UpstreamException upstream) {
            return of(upstream.retryable() ? 503 : 502, "tax-returns.upstream-unavailable", ex);
        }
        if (ex instanceof IllegalStateException) {
            return of(409, "returns.invalid-transition", ex);
        }
        if (ex instanceof IllegalArgumentException) {
            return of(400, "tax-returns.invalid-input", ex);
        }
        return Optional.empty();
    }

    private static Optional<ModuleApiException> of(int status, String code, RuntimeException ex) {
        return Optional.of(new ModuleApiException(status, code, ex.getMessage(), List.of(), ex));
    }
}
