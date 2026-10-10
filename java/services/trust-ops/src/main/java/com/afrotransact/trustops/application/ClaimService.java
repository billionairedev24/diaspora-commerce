package com.afrotransact.trustops.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.trustops.domain.ClaimStatus;
import com.afrotransact.trustops.port.ClaimHistoryEntry;
import com.afrotransact.trustops.port.Page;

/** S118 disputes and A-to-z claims: customer opens, seller responds in a window, staff decide (refund or deny). */
public interface ClaimService {

    ClaimView open(OpenClaimCommand command);

    /** The customer's own claim, the seller's claim, or any claim for staff with disputes:manage. */
    ClaimView get(UUID id);

    List<ClaimHistoryEntry> history(UUID id);

    /** The calling customer's claims, newest first. */
    Page<ClaimView> mine(int page, int size);

    /** Claims against a seller organisation (its members with support:respond, or staff). */
    Page<ClaimView> forSeller(String sellerOrgId, ClaimStatus status, int page, int size);

    /** Staff queue (disputes:manage), soonest due first; {@code status} null = open claims. */
    Page<ClaimView> queue(ClaimStatus status, String sellerOrgId, String orderId, int page, int size);

    /** Customer, seller or staff adds a media asset id as evidence. */
    ClaimView addEvidence(UUID id, String assetId, String note);

    /** The seller answers within its response window; the claim moves to staff review. */
    ClaimView sellerRespond(UUID id, String message, List<String> evidenceAssetIds);

    ClaimView escalate(UUID id, String reason);

    ClaimView withdraw(UUID id);

    /**
     * Staff decide. REFUND calls payments ({@code POST /v1/payments/{id}/refunds}, Idempotency-Key
     * {@code trustops-claim-<id>}) before the claim is resolved, so a failed refund leaves the claim open and the call
     * can be retried. Both parties are notified; the decision is audited.
     */
    ClaimView decide(UUID id, DecideClaimCommand command);
}
