package com.afrotransact.platform.web.error;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.ModuleApiException;

/**
 * Turns the platform's typed errors into the {@link ModuleApiException} an in-process module API call throws, with the status
 * {@link ProblemDetailsAdvice} gives them over HTTP. A module's own error types are mapped by that module, then fall back here.
 */
public final class ModuleFailures {

    private ModuleFailures() {
    }

    /** The mapping of {@link ProblemDetailsAdvice}, plus 400 for {@link IllegalArgumentException}; empty for anything else. */
    public static Optional<ModuleApiException> ofPlatform(RuntimeException ex) {
        if (ex instanceof ModuleApiException already) {
            return Optional.of(already);
        }
        if (ex instanceof DomainException domain) {
            int status = ex instanceof NotFoundException ? 404 : ex instanceof ConflictException ? 409
                    : ex instanceof BusinessRuleException ? 422 : ex instanceof ForbiddenActionException ? 403 : 500;
            return Optional.of(new ModuleApiException(status, domain.code(), domain.getMessage(), List.of(), ex));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new ModuleApiException(400, "invalid-input", ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }
}
