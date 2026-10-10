package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;

/**
 * Verification results read from the Stripe connected account. Never holds ID images or full tax numbers.
 *
 * @param identityStatus {@code verified | pending | unverified} from individual/representative verification
 * @param taxIdStatus    {@code verified | pending | failed} derived from requirements and errors
 */
public record StripeVerification(String accountId, boolean detailsSubmitted, boolean chargesEnabled,
                                 boolean payoutsEnabled, String identityStatus, String taxIdStatus,
                                 List<String> currentlyDue, String disabledReason, Instant refreshedAt) {

    public StripeVerification {
        currentlyDue = currentlyDue == null ? List.of() : List.copyOf(currentlyDue);
    }

    /** Verification rows derived from the account's requirements (identity and IRS name/TIN match). */
    public static StripeVerification from(String accountId, RequirementsSnapshot r, Instant now) {
        boolean taxError = r.errors().stream().anyMatch(e -> isTaxId(e.requirement()));
        boolean taxOpen = r.open().stream().anyMatch(StripeVerification::isTaxId);
        boolean taxPending = r.pendingVerification().stream().anyMatch(StripeVerification::isTaxId);
        String tax = taxError ? "failed" : taxOpen ? "required" : taxPending ? "pending"
                : r.detailsSubmitted() ? "verified" : "pending";
        String identity = r.individualVerificationStatus() != null ? r.individualVerificationStatus()
                : r.detailsSubmitted() && r.open().stream().noneMatch(k -> k.contains("verification"))
                ? "verified" : "pending";
        return new StripeVerification(accountId, r.detailsSubmitted(), r.chargesEnabled(), r.payoutsEnabled(),
                identity, tax, List.copyOf(r.open()), r.disabledReason(), now);
    }

    private static boolean isTaxId(String key) {
        return key != null && (key.endsWith("tax_id") || key.endsWith("id_number") || key.endsWith("ssn_last_4"));
    }

    public static StripeVerification created(String accountId, Instant now) {
        return new StripeVerification(accountId, false, false, false, "unverified", "pending", List.of(), null, now);
    }
}
