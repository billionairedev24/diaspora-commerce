package com.afrotransact.payments.api;

import com.afrotransact.module.EndUserActor;

/**
 * Payout balance and requests of an organisation for the signed-in person (MCP tools): the in-process equivalent of relaying their
 * bearer token to payments-service. Payments' own checks decide as for the HTTP request (members see their own organisation, staff
 * name one; the finance queue needs {@code payouts:approve}). Read only. The calling module needs the delegation scope
 * {@value #READ_SCOPE}. Results are the JSON tree of the HTTP response; failures are {@code ModuleApiException} with the HTTP problem's
 * status and code.
 */
public interface PayoutReadApi {

    /** Delegation scope for this API. */
    String READ_SCOPE = "payments:user.read";

    /** {@code GET /v1/payouts/balance?orgId=}. */
    Object balance(EndUserActor user, String orgId);

    /** {@code GET /v1/payouts/requests?orgId=&limit=}: the organisation's payout requests, newest first. */
    Object requests(EndUserActor user, String orgId, int limit);
}
