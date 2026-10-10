package com.afrotransact.ai.adapter.out.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.afrotransact.ai.domain.UsageTotals;
import com.afrotransact.ai.port.UsageLedger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

/** Ledger rows plus a daily roll-up per user×feature and per feature (upserted in the same transaction). */
@Repository
class JdbcUsageLedger implements UsageLedger {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    JdbcUsageLedger(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void record(Entry e) {
        LocalDate day = LocalDate.ofInstant(e.at(), ZoneOffset.UTC);
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into ai_usage (id, usage_day, occurred_at, subject, feature, task, model, prompt_tokens,
                                          completion_tokens, cost_micros, degraded, fallback)
                    values (:id, :day, :at, :subject, :feature, :task, :model, :prompt, :completion, :cost,
                            :degraded, :fallback)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("day", Date.valueOf(day))
                    .param("at", Timestamp.from(e.at()))
                    .param("subject", e.subject())
                    .param("feature", e.feature())
                    .param("task", e.task())
                    .param("model", e.model())
                    .param("prompt", e.usage().promptTokens())
                    .param("completion", e.usage().completionTokens())
                    .param("cost", e.costMicros())
                    .param("degraded", e.degraded())
                    .param("fallback", e.fallback())
                    .update();
            rollUp(day, "USER", userKey(e.subject(), e.feature()), e.usage().total(), e.costMicros());
            rollUp(day, "FEATURE", e.feature(), e.usage().total(), e.costMicros());
        });
    }

    @Override
    public UsageTotals userTotals(String subject, String feature, LocalDate day) {
        return totals(day, "USER", userKey(subject, feature));
    }

    @Override
    public UsageTotals featureTotals(String feature, LocalDate day) {
        return totals(day, "FEATURE", feature);
    }

    private void rollUp(LocalDate day, String scope, String key, long tokens, long cost) {
        jdbc.sql("""
                insert into ai_usage_daily (usage_day, scope, scope_key, tokens, cost_micros)
                values (:day, :scope, :key, :tokens, :cost)
                on conflict (usage_day, scope, scope_key) do update
                   set tokens = ai_usage_daily.tokens + excluded.tokens,
                       cost_micros = ai_usage_daily.cost_micros + excluded.cost_micros,
                       version = ai_usage_daily.version + 1
                """)
                .param("day", Date.valueOf(day))
                .param("scope", scope)
                .param("key", key)
                .param("tokens", tokens)
                .param("cost", cost)
                .update();
    }

    private UsageTotals totals(LocalDate day, String scope, String key) {
        return jdbc.sql("""
                select tokens, cost_micros from ai_usage_daily
                where usage_day = :day and scope = :scope and scope_key = :key
                """)
                .param("day", Date.valueOf(day))
                .param("scope", scope)
                .param("key", key)
                .query((rs, n) -> new UsageTotals(rs.getLong("tokens"), rs.getLong("cost_micros")))
                .optional()
                .orElse(UsageTotals.ZERO);
    }

    private static String userKey(String subject, String feature) {
        return subject + "|" + feature;
    }
}
