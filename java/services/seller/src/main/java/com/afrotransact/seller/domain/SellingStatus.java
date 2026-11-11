package com.afrotransact.seller.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The answer to "can this seller sell this product?": SELL, APPLY or NOT_AVAILABLE, why in plain words, and what is
 * missing, one entry per scope that needs approval (or forbids selling).
 *
 * @param pendingFacts facts the answer depends on that the form has not given yet (jewelry: "material")
 */
public record SellingStatus(SellingLevel level, String reason, List<Missing> missing, List<String> pendingFacts) {

    public SellingStatus {
        missing = missing == null ? List.of() : List.copyOf(missing);
        pendingFacts = pendingFacts == null ? List.of() : List.copyOf(pendingFacts);
    }

    public static SellingStatus sell() {
        return new SellingStatus(SellingLevel.SELL, null, List.of(), List.of());
    }

    /**
     * One scope in the way.
     *
     * @param level          APPLY or NOT_AVAILABLE
     * @param state          NOT_APPLIED, APPLIED, NEEDS_ATTENTION, EXPIRED or NOT_AVAILABLE
     * @param canApply       the seller can apply (or reapply, or renew) now
     * @param applicationId  the seller's current application for it, if any
     * @param applicationStatus its status (INFO_REQUESTED means "update it", REJECTED "apply again")
     */
    public record Missing(SellingScope scope, String label, SellingLevel level, SellingApprovalStatus state,
                          String message, boolean canApply, LocalDate reapplyAfter,
                          List<SellingDocumentRequirement> documents, String applicationId,
                          String applicationStatus) {

        public Missing {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
}
