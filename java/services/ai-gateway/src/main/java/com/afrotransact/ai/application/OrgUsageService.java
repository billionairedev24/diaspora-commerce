package com.afrotransact.ai.application;

import java.time.LocalDate;
import java.util.List;

/**
 * AI usage of seller and provider organisations per UTC day, in AI credits, for seller-service's metered billing
 * (S206; service scope {@code ai:usage.read}) and AI staff ({@code ai:manage}).
 */
public interface OrgUsageService {

    /** Maximum number of days one request may cover. */
    int MAX_DAYS = 93;

    List<OrgDayCredits> usage(Caller caller, LocalDate from, LocalDate to);

    /**
     * One organisation's usage on one UTC day.
     *
     * @param credits    AI credits (estimated cost rounded up to whole credits, see {@code AiCredits})
     * @param costMicros estimated model cost in micro-dollars
     */
    record OrgDayCredits(String orgId, LocalDate day, long calls, long tokens, long costMicros, long credits) {
    }
}
