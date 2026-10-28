package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.GrantSource;
import com.afrotransact.seller.domain.GrantType;

/** The grants log (rule rewards and staff adjustments). */
public interface BillingGrantRepository {

    /** Inserts unless the (rule, org) or (org, request key) already exists; returns the stored grant either way. */
    BillingGrant insertOnce(BillingGrant g);

    Optional<BillingGrant> find(UUID id);

    BillingGrant update(BillingGrant g);

    /** Applied coupons of the organisation with invoices left, oldest first (they stack). */
    List<BillingGrant> activeCoupons(String orgId);

    /** A coupon was used on one invoice: one invoice fewer left. */
    void useCouponOnce(UUID id);

    List<BillingGrant> forOrg(String orgId, int limit);

    /** Grants log for staff, newest first, filtered and paged. */
    Page<BillingGrant> search(GrantFilter filter, int page, int size);

    /** Rules already granted to an organisation. */
    Set<UUID> grantedRules(String orgId);

    /** Organisations that already have a grant of a rule. */
    Set<String> grantedOrgs(UUID ruleId);

    /** PENDING / FAILED grants older than {@code before} with fewer than {@code maxAttempts} attempts. */
    List<BillingGrant> retryable(Instant before, int maxAttempts, int limit);

    /** Staff reason codes for adjustments. */
    List<ReasonCode> reasonCodes();

    /**
     * Grants log filter (every field optional).
     *
     * @param q matches the organisation id or the summary
     */
    record GrantFilter(String orgId, GrantSource source, GrantType type, UUID ruleId, String q) {
    }

    /** An adjustment reason. */
    record ReasonCode(String code, String label) {
    }
}
