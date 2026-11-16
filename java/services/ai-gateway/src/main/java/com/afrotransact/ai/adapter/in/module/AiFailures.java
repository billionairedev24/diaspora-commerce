package com.afrotransact.ai.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.BudgetExceededException;
import com.afrotransact.ai.domain.RateLimitedException;
import com.afrotransact.ai.port.ModelUnavailableException;
import com.afrotransact.module.ModuleApiException;

/** ai-gateway's own error types as the status and code {@code AiErrorAdvice} gives them over HTTP. */
final class AiFailures {

    private AiFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof BudgetExceededException budget) {
            return Optional.of(new ModuleApiException(429, BudgetExceededException.CODE, budget.getMessage(), List.of(), ex));
        }
        if (ex instanceof RateLimitedException limited) {
            return Optional.of(new ModuleApiException(429, RateLimitedException.CODE, limited.getMessage(), List.of(), ex));
        }
        if (ex instanceof ModelUnavailableException) {
            return Optional.of(new ModuleApiException(503, ModelUnavailableException.CODE,
                    "No configured model answered; try again shortly", List.of(), ex));
        }
        if (ex instanceof AiRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, rule.code(), rule.getMessage(), List.of(), ex));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new ModuleApiException(400, "ai.invalid-input", ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }
}
