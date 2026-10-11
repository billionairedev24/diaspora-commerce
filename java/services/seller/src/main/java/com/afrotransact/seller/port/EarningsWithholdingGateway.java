package com.afrotransact.seller.port;

/**
 * payments-service: withhold a plan fee from the organisation's next settlement transfer (S297, payments side in the
 * backlog). Idempotent on {@code invoiceId}.
 */
public interface EarningsWithholdingGateway {

    /** Whether the integration is switched on (the feature flag). */
    boolean enabled();

    Withholding withhold(String orgId, String invoiceId, long amountCents, String currency);

    /**
     * payments' answer.
     *
     * @param withheld   true when the amount will be deducted from the next transfer
     * @param reference  payments' withholding id
     * @param reason     why it was declined (not enough pending earnings, payouts paused…)
     */
    record Withholding(boolean withheld, String reference, String reason) {
    }
}
