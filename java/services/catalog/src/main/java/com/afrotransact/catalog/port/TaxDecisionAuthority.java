package com.afrotransact.catalog.port;

import java.util.Collection;
import java.util.Map;

/**
 * The one authority on "does this variant have a clear tax decision?": tax-returns' stored decision, the same record
 * checkout's quote reads ({@code POST /v1/tax/listing-decisions/status}). Catalog's own copy of a variant's tax state is
 * a cache for forms and can be stale or wrong (for example a local profile that skips the review); a listing is on sale
 * only when this port answers RESOLVED for every variant. Never invents an answer: when tax-returns cannot be reached the
 * call fails with {@link Unavailable} and the caller keeps the current state.
 */
public interface TaxDecisionAuthority {

    /**
     * False only where the authority is switched off explicitly (tests and stacks without tax-returns, property
     * {@code at.catalog.tax.authority=off}): the gate then relies on catalog's own copy and logs a warning at start-up.
     */
    boolean consulted();

    /**
     * Batched. @return one answer per distinct ATIN asked, keyed by ATIN; an ATIN the service did not answer is absent
     * and callers treat it as not resolved
     * @throws Unavailable when tax-returns cannot be reached or answers badly (timeouts, 5xx)
     */
    Map<String, Answer> ask(Collection<Ask> asks);

    /** @param sellerOrgId the listing owner, whose behaviour the decision is read under */
    record Ask(String atin, String sellerOrgId) {
    }

    /**
     * @param decisionStatus RESOLVED, MISSING_FACTS, BLOCKED, NEEDS_APPROVAL, REFINE_CODE or CONFLICT_OPEN
     * @param recordStatus   tax-returns' record status (APPROVED, PENDING_REVIEW ...) or null when it has no record
     */
    record Answer(String atin, String decisionStatus, String recordStatus, String reason) {

        public boolean resolved() {
            return "RESOLVED".equals(decisionStatus);
        }
    }

    /** tax-returns cannot be reached or failed: nothing is known; never treated as OK. */
    class Unavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
