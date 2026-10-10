package com.afrotransact.ai.adapter.in.web;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.BudgetExceededException;
import com.afrotransact.ai.port.ModelUnavailableException;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps ai-gateway errors to RFC 9457 problems with stable {@code ai.*} codes. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AiErrorAdvice {

    @ExceptionHandler(BudgetExceededException.class)
    ResponseEntity<ProblemDetail> budget(BudgetExceededException ex) {
        ProblemDetail pd = problem(HttpStatus.TOO_MANY_REQUESTS, BudgetExceededException.CODE,
                "AI budget exceeded", ex.getMessage());
        pd.setProperty("feature", ex.feature());
        pd.setProperty("scope", ex.scope());
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        long untilMidnight = Duration.between(now, LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)).toSeconds();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(untilMidnight, 1)))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(ModelUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(ModelUnavailableException ex) {
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, ModelUnavailableException.CODE,
                "AI model unavailable", "No configured model answered; try again shortly");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(AiRuleViolation.class)
    ResponseEntity<ProblemDetail> rule(AiRuleViolation ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.code(), "Invalid AI request", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> illegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(HttpStatus.BAD_REQUEST, "ai.invalid-input", "Invalid input", ex.getMessage()));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(ProblemTypes.of(code));
        pd.setTitle(title);
        pd.setProperty("code", code);
        return pd;
    }
}
