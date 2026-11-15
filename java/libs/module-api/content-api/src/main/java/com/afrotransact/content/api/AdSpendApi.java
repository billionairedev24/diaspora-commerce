package com.afrotransact.content.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Charged ads spend per organisation and day, which seller reads for metered billing over HTTP today
 * ({@code GET /v1/ads/spend}, scope {@code ads:spend.read}), entered in-process under the calling module's principal.
 * A caller without the scope is refused with {@code ModuleApiException} status 403.
 */
public interface AdSpendApi {

    /** Inclusive range of at most 93 days; only days with spend are listed. */
    List<OrgDaySpend> spend(LocalDate from, LocalDate to);

    record OrgDaySpend(String sellerOrgId, LocalDate day, String currency, long spendMinor) {
    }
}
