package com.afrotransact.content.domain.careers;

import java.time.Instant;

/** Validates an application against the role and the form rules. */
public interface ApplicationPolicy {

    /**
     * Throws {@code ContentRuleViolation} with per-field errors, or returns the normalised application without an
     * id or reference (issued only once the application is valid, so references have no gaps from bad input).
     */
    JobApplication validate(Job job, ApplicationDraft draft, Instant now);
}
