package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.ReviewMode;
import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.profile.ReviewSettingsChange;
import com.afrotransact.taxreturns.port.TaxReviewSettingsRepository;
import com.afrotransact.taxreturns.port.TaxSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores the review settings as one JSON value under the key {@code tax.review}; a missing row means the default (RISK_BASED), an unreadable one ALL. */
@Repository
class JdbcTaxReviewSettingsRepository implements TaxReviewSettingsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcTaxReviewSettingsRepository.class);
    private static final String KEY = "tax.review";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcTaxReviewSettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ReviewSettings current() {
        return jdbc.sql("select value from tax_setting where key = :k").param("k", KEY).query(String.class)
                .optional().map(this::parse).orElseGet(ReviewSettings::defaults);
    }

    @Override
    public Optional<TaxSettingsRepository.Stamp> stamp() {
        return jdbc.sql("select updated_by, updated_at from tax_setting where key = :k").param("k", KEY)
                .query((rs, n) -> new TaxSettingsRepository.Stamp(rs.getString("updated_by"),
                        rs.getTimestamp("updated_at").toInstant())).optional();
    }

    @Override
    public void change(ReviewSettings to, String reason, String by, Instant at) {
        String before = jdbc.sql("select value from tax_setting where key = :k").param("k", KEY).query(String.class)
                .optional().orElse(null);
        String value = json.write(Map.of("mode", to.mode().name(), "alwaysReview", to.alwaysReview(),
                "auditPercent", to.auditPercent()));
        jdbc.sql("""
                insert into tax_setting (key, value, updated_by, updated_at) values (:k, :v, :by, :at)
                on conflict (key) do update set value = :v, updated_by = :by, updated_at = :at
                """)
                .param("k", KEY).param("v", value).param("by", by).param("at", Timestamp.from(at)).update();
        jdbc.sql("""
                insert into tax_setting_history (key, old_value, new_value, reason, changed_by, changed_at)
                values (:k, :old, :new, :reason, :by, :at)
                """)
                .param("k", KEY).param("old", before).param("new", value).param("reason", reason)
                .param("by", by).param("at", Timestamp.from(at)).update();
    }

    @Override
    public List<ReviewSettingsChange> history(int limit) {
        return jdbc.sql("select * from tax_setting_history where key = :k order by changed_at desc, id desc limit :n")
                .param("k", KEY).param("n", limit)
                .query((rs, n) -> new ReviewSettingsChange(rs.getString("old_value") == null ? null
                        : parse(rs.getString("old_value")), parse(rs.getString("new_value")), rs.getString("reason"),
                        rs.getString("changed_by"), rs.getTimestamp("changed_at").toInstant())).list();
    }

    /** An unreadable value falls back to ALL (every classification reviewed): staff may have chosen it on purpose. */
    private ReviewSettings parse(String value) {
        try {
            Map<String, Object> m = json.readMap(value);
            Object percent = m.get("auditPercent");
            Object always = m.get("alwaysReview");
            List<String> categories = always instanceof List<?> l ? l.stream().map(String::valueOf).toList() : null;
            return new ReviewSettings(ReviewMode.valueOf(String.valueOf(m.get("mode"))), categories,
                    percent instanceof Number n ? n.intValue() : ReviewSettings.DEFAULT_AUDIT_PERCENT);
        } catch (RuntimeException e) {
            LOG.error("The stored tax review settings cannot be read; every classification stays in review", e);
            return ReviewSettings.safeFallback();
        }
    }
}
