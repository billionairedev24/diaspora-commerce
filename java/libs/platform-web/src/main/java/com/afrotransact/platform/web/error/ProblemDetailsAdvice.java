package com.afrotransact.platform.web.error;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps typed domain errors and validation failures to RFC 9457 problem details. */
@RestControllerAdvice
public class ProblemDetailsAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);
    private static final String TYPE_BASE = "https://developers.afrotransact.com/problems/";

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail businessRule(BusinessRuleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex);
    }

    @ExceptionHandler(ForbiddenActionException.class)
    public ProblemDetail forbidden(ForbiddenActionException ex) {
        return problem(HttpStatus.FORBIDDEN, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setType(URI.create(TYPE_BASE + "validation"));
        pd.setTitle("Invalid request");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ProblemDetailsAdvice::fieldError)
                .toList();
        pd.setProperty("errors", errors);
        return pd;
    }

    private static Map<String, String> fieldError(FieldError fe) {
        return Map.of("field", fe.getField(),
                "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
    }

    private static ProblemDetail problem(HttpStatus status, DomainException ex) {
        LOG.debug("Domain error {}: {}", ex.code(), ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setType(URI.create(TYPE_BASE + ex.code()));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", ex.code());
        return pd;
    }
}
