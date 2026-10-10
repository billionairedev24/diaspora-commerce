package com.afrotransact.taxreturns.domain.returns;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Return eligibility, request validation and approval rules. */
public interface ReturnPolicy {

    /** Eligibility of one delivered line of a shipment. */
    LineEligibility eligibility(DeliveredLine line, FulfilmentType fulfilment, DeliveryMethod delivery,
                                Optional<SellerReturnPolicy> seller, Instant now);

    /**
     * Validates a request against the eligibility of its lines. Throws {@link ReturnRuleViolation} with every failing
     * field at once.
     */
    void validate(ReturnMethod method, List<ReturnLineRequest> lines, Map<String, LineEligibility> eligibility,
                  Map<String, DeliveredLine> delivered);

    /** True when the request needs no human approval. */
    boolean autoApprove(FulfilmentType fulfilment, ReturnMethod method, long estimateMinor);

    /** When a seller must decide by (seller-fulfilled requests that are not auto-approved). */
    Instant approvalDueAt(Instant requestedAt);

    /** Pay the refund at the first carrier / driver scan instead of after inspection. */
    boolean refundAtFirstScan(FulfilmentType fulfilment, ReturnMethod method, long estimateMinor,
                              Optional<SellerReturnPolicy> seller);

    /** Restocking fee in basis points for no-fault returns (seller policy, capped). */
    int restockingFeeBps(FulfilmentType fulfilment, Optional<SellerReturnPolicy> seller);

    /** Days to drop the parcel off once the label is issued. */
    int dropOffDays();
}
