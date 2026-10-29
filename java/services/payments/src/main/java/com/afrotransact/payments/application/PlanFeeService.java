package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.PlanFeeDeduction;
import com.afrotransact.payments.domain.PlanFeeWithholding;

/**
 * Seller plan fees collected from earnings (S298). The seller service owns the plan and the Stripe invoice; when an
 * organisation pays its plan "from its sales" it asks payments, before Stripe finalises the invoice, to withhold up to
 * the invoice amount from the organisation's delivered but not yet transferred earnings (never from orders under an
 * open dispute). The withheld amount is debited from the seller payable at once and the organisation's next
 * transfers are smaller by it; the seller service then credits the Stripe customer balance by the same amount, so the
 * card pays only the remainder. A voided or refunded invoice releases the withholding back to the next transfer.
 */
public interface PlanFeeService {

    /**
     * Withholds up to {@code cmd.amountMinor()} (seller service only, payments:plan-fees). Idempotent per invoice: the
     * key must be {@code plan-fee-<invoiceId>} and a replay answers the first decision (WITHHELD, possibly partial, or
     * DECLINED); a replay with another amount or organisation is 409.
     */
    PlanFeeWithholding withhold(String orgId, PlanFeeCommand cmd, String idempotencyKey);

    /**
     * Releases {@code amountMinor} (null: everything still withheld) of the invoice's withholding back to the
     * organisation (seller service only). Idempotent per key ({@code plan-fee-release-<invoice or credit note>}).
     */
    PlanFeeWithholding release(String orgId, String invoiceId, Long amountMinor, String reason,
                               String idempotencyKey);

    /** The organisation's withholdings, newest first (the seller service, its members, or staff). */
    List<View> ofOrg(String orgId, int limit);

    /** A withholding with the transfers that settled it. */
    record View(PlanFeeWithholding withholding, List<PlanFeeDeduction> deductions) {

        public View {
            deductions = List.copyOf(deductions);
        }
    }

    /**
     * A withholding request.
     *
     * @param invoiceId     the Stripe invoice (in_…)
     * @param invoiceNumber the invoice number sellers see, when Stripe assigned one (drafts have none yet)
     * @param amountMinor   what the invoice still needs after credits
     */
    record PlanFeeCommand(String invoiceId, String invoiceNumber, long amountMinor, String currency) {
    }
}
