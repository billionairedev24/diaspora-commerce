package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.Subscription;

/**
 * Issues the plan invoice for a billing period (the periods come from the subscription's RRULE): the plan fee at the
 * subscription's own plan version, plus metered usage not yet billed, less what the charge gate waives and the coupons
 * that stack on the plan fee. Idempotent per organisation and period start.
 */
public interface InvoiceIssuingService {

    /**
     * Issues the invoice for {@code [periodStart, periodEnd)} inside the caller's transaction (the caller holds the
     * subscription's row lock). Empty when there is nothing to invoice (a free plan without usage) or the period was
     * invoiced already. A fully waived or covered invoice is issued as {@code paid}; otherwise it is {@code open} and
     * {@link InvoiceCollectionService} settles it after the transaction commits.
     *
     * @param reason {@link Invoice#REASON_CREATE} for the first paid period, {@link Invoice#REASON_CYCLE} after
     */
    Optional<Invoice> issue(Subscription sub, Instant periodStart, Instant periodEnd, String reason);

    /**
     * Issues the invoice for an upgrade in the middle of a paid period: {@code amountCents} is the price difference for
     * the time left. Several can exist in one period.
     */
    Optional<Invoice> issueUpgrade(Subscription sub, Plan target, long amountCents, Instant now);
}
