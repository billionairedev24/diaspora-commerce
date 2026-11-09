package com.afrotransact.mcp.port;

/** tax-returns: the category tax review queue and the shadow report (read only, tax:read). */
public interface TaxApi {

    /**
     * {@code GET /v1/tax/variant-classifications}: product variants by review status; PENDING_REVIEW is the queue,
     * APPROVED / REJECTED / STALE are the decisions with who decided and when.
     */
    Downstream reviewQueue(String status, String query, int page, int size, String bearerToken);

    /**
     * {@code GET /v1/tax/shadow/report}: a provider's tax quote against the in-house quote on real orders between two
     * dates (ISO), grouped by internal tax code. Shadow quotes are never charged.
     */
    Downstream shadowReport(String from, String to, String provider, int size, String bearerToken);
}
