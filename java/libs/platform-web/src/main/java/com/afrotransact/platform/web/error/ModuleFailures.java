package com.afrotransact.platform.web.error;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.ModuleApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

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

    /**
     * The request-body validation the HTTP endpoint gets from {@code @Valid}, for an in-process call that builds the same request record
     * itself: a violation is the 400 {@code validation} the HTTP advice answers, one line per field ({@code field: message}).
     */
    public static void validate(Validator validator, Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            List<String> lines = violations.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList();
            throw new ModuleApiException(400, "validation", "Request validation failed", lines, null);
        }
    }
}
