package com.afrotransact.ai.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.domain.UsageTotals;

/** Append-only ledger of model usage (from provider usage metadata) with estimated cost. */
public interface UsageLedger {

    void record(Entry entry);

    UsageTotals userTotals(String subject, String feature, LocalDate day);

    UsageTotals featureTotals(String feature, LocalDate day);

    /** Billable usage per organisation and UTC day in [from, to], ordered by organisation and day. */
    List<OrgDayUsage> orgUsage(LocalDate from, LocalDate to);

    /**
     * One model call.
     *
     * @param subject    caller subject (user or client id)
     * @param orgId      organisation billed for the call (seller / provider members), null when not billable
     * @param feature    feature key, for example {@code lola}
     * @param task       task name
     * @param model      model that answered
     * @param usage      tokens
     * @param costMicros estimated cost in micro-dollars
     * @param degraded   served by the cheapest model because a budget was reached
     * @param fallback   served by a fallback model
     * @param at         when
     */
    record Entry(String subject, String orgId, String feature, String task, String model, TokenUsage usage, long costMicros,
                 boolean degraded, boolean fallback, Instant at) {
    }

    /**
     * Usage of one organisation on one UTC day.
     *
     * @param calls      model calls
     * @param tokens     prompt + completion tokens
     * @param costMicros estimated cost in micro-dollars
     */
    record OrgDayUsage(String orgId, LocalDate day, long calls, long tokens, long costMicros) {
    }
}
