package com.afrotransact.ai.port;

import java.time.Instant;
import java.time.LocalDate;

import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.domain.UsageTotals;

/** Append-only ledger of model usage (from provider usage metadata) with estimated cost. */
public interface UsageLedger {

    void record(Entry entry);

    UsageTotals userTotals(String subject, String feature, LocalDate day);

    UsageTotals featureTotals(String feature, LocalDate day);

    /**
     * One model call.
     *
     * @param subject    caller subject (user or client id)
     * @param feature    feature key, for example {@code lola}
     * @param task       task name
     * @param model      model that answered
     * @param usage      tokens
     * @param costMicros estimated cost in micro-dollars
     * @param degraded   served by the cheapest model because a budget was reached
     * @param fallback   served by a fallback model
     * @param at         when
     */
    record Entry(String subject, String feature, String task, String model, TokenUsage usage, long costMicros,
                 boolean degraded, boolean fallback, Instant at) {
    }
}
