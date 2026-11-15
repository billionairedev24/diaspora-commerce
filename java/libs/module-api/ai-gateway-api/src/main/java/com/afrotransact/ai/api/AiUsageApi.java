package com.afrotransact.ai.api;

import java.time.LocalDate;
import java.util.List;

/**
 * AI credits per organisation and day, which seller reads for metered billing over HTTP today
 * ({@code GET /v1/ai/usage/orgs}, scope {@code ai:usage.read}), entered in-process under the calling module's principal.
 * A caller without the scope is refused with {@code ModuleApiException} status 403.
 */
public interface AiUsageApi {

    /** Inclusive range of at most 93 days; only days with usage are listed. */
    List<OrgDayUsage> usage(LocalDate from, LocalDate to);

    record OrgDayUsage(String orgId, LocalDate day, long credits) {
    }
}
