package com.afrotransact.content.application;

import java.util.Objects;

/**
 * Sponsored-products settings.
 *
 * <p>AfroTransact Retail ({@code retailOrgId}) is the first-party merchant of record, not a marketplace seller, so its
 * campaigns are <b>internal</b>: they enter the same second-price auction with a bid and a budget, and clicks are
 * recorded at the second price, but that budget is only an internal pacing cap (it keeps Retail from taking every
 * sponsored slot) and the recorded spend is a notional cost for ROAS reporting. It is never billed: Retail is left out
 * of {@code GET /v1/ads/spend}, so seller-service's metered billing never sees it.
 *
 * @param retailOrgId organisation id of AfroTransact Retail
 */
public record AdsSettings(String retailOrgId) {

    public AdsSettings {
        Objects.requireNonNull(retailOrgId, "retailOrgId");
    }

    /** True for AfroTransact Retail's internal campaigns. */
    public boolean isInternal(String sellerOrgId) {
        return retailOrgId.equals(sellerOrgId);
    }
}
