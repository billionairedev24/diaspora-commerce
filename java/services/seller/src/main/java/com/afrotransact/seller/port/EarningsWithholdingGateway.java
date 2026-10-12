package com.afrotransact.seller.port;

/**
 * payments-service (S298): withhold a plan invoice from the organisation's earnings, and release it again. Idempotent
 * on the invoice id ({@code plan-fee-<invoice>}) and on the release key ({@code plan-fee-release-<…>}).
 */
public interface EarningsWithholdingGateway {

    /** Whether the integration is switched on (the kill switch {@code COLLECT_PLAN_FEES_FROM_EARNINGS}). */
    boolean enabled();

    /** Asks payments to withhold up to {@code amountCents}; payments may withhold less (the card pays the rest). */
    Withholding withhold(String orgId, String invoiceId, String invoiceNumber, long amountCents, String currency);

    /**
     * Releases {@code amountCents} of the invoice's withholding back to the organisation's next transfer.
     *
     * @return what payments has released for the invoice in total
     */
    long release(String orgId, String invoiceId, long amountCents, String reason, String idempotencyKey);

    /**
     * payments' answer.
     *
     * @param withheld      true when something will be deducted from the next transfers
     * @param withheldCents how much (at most what was asked; zero when declined)
     * @param reference     payments' withholding id
     * @param reason        why less (or nothing) was withheld: not enough delivered, undisputed earnings…
     */
    record Withholding(boolean withheld, long withheldCents, String reference, String reason) {
    }
}
