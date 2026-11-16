package com.afrotransact.taxreturns.api;

import com.afrotransact.module.EndUserActor;

/**
 * The category tax review queue and the shadow report for the signed-in person (MCP tax tools, read only): the in-process equivalent of
 * relaying their bearer token to tax-returns. Tax-returns' own permission check ({@code tax:read}) decides as for the HTTP request.
 * The calling module needs the delegation scope {@value #READ_SCOPE}. Results are the JSON tree of the HTTP response; failures are
 * {@code ModuleApiException} with the HTTP problem's status and code.
 */
public interface TaxReviewReadApi {

    /** Delegation scope for this API. */
    String READ_SCOPE = "tax-returns:user.read";

    /** {@code GET /v1/tax/variant-classifications}; {@code status} is a record status (PENDING_REVIEW is the queue), {@code query} optional. */
    Object reviewQueue(EndUserActor user, String status, String query, int page, int size);

    /** {@code GET /v1/tax/shadow/report?onlyDifferences=true}: {@code from} and {@code to} ISO dates, {@code provider} optional. */
    Object shadowReport(EndUserActor user, String from, String to, String provider, int size);
}
