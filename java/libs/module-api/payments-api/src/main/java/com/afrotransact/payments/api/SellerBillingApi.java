package com.afrotransact.payments.api;

/**
 * What seller asks payments for billing, over HTTP today, entered in-process under the calling module's principal: registering
 * the Stripe Connect account ({@code PUT /v1/connected-accounts/{orgId}}) and collecting plan fees from earnings
 * ({@code POST /v1/orgs/{orgId}/plan-fee-withholdings[/{invoice}/release]}, scope {@code payments:plan-fees}). A caller without the
 * scope is refused with {@code ModuleApiException} status 403; failures carry the status the endpoint answers (404, 409, 422).
 */
public interface SellerBillingApi {

    /** Registers (or replaces) the organisation's Stripe Connect account (scope {@code payments:connected-accounts}). */
    void registerConnectedAccount(String orgId, String stripeAccountId);

    /** Withholds up to {@code amountMinor} of a plan invoice from the organisation's earnings; idempotent per key. */
    Withholding withhold(String orgId, String invoiceId, String invoiceNumber, long amountMinor, String currency,
                         String idempotencyKey);

    /** Gives a withheld plan fee back; returns the amount released (0 when nothing was withheld, status 404). */
    long release(String orgId, String invoiceId, long amountMinor, String reason, String idempotencyKey);

    /** @param status WITHHELD or DECLINED */
    record Withholding(String id, String status, long withheldMinor, String reason) {
    }
}
