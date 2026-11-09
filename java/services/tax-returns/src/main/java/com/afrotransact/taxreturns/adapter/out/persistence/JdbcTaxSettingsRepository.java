package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxMethodChange;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.TaxSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxSettingsRepository implements TaxSettingsRepository {

    private static final String METHOD = "tax.method";
    private static final String SHADOW = "tax.shadow_provider";
    private static final String OFF = "OFF";

    private final JdbcClient jdbc;

    JdbcTaxSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TaxMethod> method() {
        return jdbc.sql("select value from tax_setting where key = :k").param("k", METHOD).query(String.class)
                .optional().map(TaxMethod::valueOf);
    }

    @Override
    public Optional<Stamp> methodStamp() {
        return jdbc.sql("select updated_by, updated_at from tax_setting where key = :k").param("k", METHOD)
                .query((rs, n) -> new Stamp(rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    @Override
    public void changeMethod(TaxMethod to, String reason, String by, Instant at) {
        String before = jdbc.sql("select value from tax_setting where key = :k").param("k", METHOD)
                .query(String.class).optional().orElse(null);
        jdbc.sql("""
                insert into tax_setting (key, value, updated_by, updated_at) values (:k, :v, :by, :at)
                on conflict (key) do update set value = :v, updated_by = :by, updated_at = :at
                """)
                .param("k", METHOD).param("v", to.name()).param("by", by).param("at", Timestamp.from(at)).update();
        jdbc.sql("""
                insert into tax_setting_history (key, old_value, new_value, reason, changed_by, changed_at)
                values (:k, :old, :new, :reason, :by, :at)
                """)
                .param("k", METHOD).param("old", before).param("new", to.name()).param("reason", reason)
                .param("by", by).param("at", Timestamp.from(at)).update();
    }

    @Override
    public Optional<TaxProviderId> shadowProvider() {
        return jdbc.sql("select value from tax_setting where key = :k").param("k", SHADOW).query(String.class)
                .optional().filter(v -> !OFF.equals(v)).map(TaxProviderId::valueOf);
    }

    @Override
    public void changeShadowProvider(TaxProviderId to, String reason, String by, Instant at) {
        String before = jdbc.sql("select value from tax_setting where key = :k").param("k", SHADOW)
                .query(String.class).optional().orElse(null);
        String value = to == null ? OFF : to.name();
        jdbc.sql("""
                insert into tax_setting (key, value, updated_by, updated_at) values (:k, :v, :by, :at)
                on conflict (key) do update set value = :v, updated_by = :by, updated_at = :at
                """)
                .param("k", SHADOW).param("v", value).param("by", by).param("at", Timestamp.from(at)).update();
        jdbc.sql("""
                insert into tax_setting_history (key, old_value, new_value, reason, changed_by, changed_at)
                values (:k, :old, :new, :reason, :by, :at)
                """)
                .param("k", SHADOW).param("old", before).param("new", value).param("reason", reason)
                .param("by", by).param("at", Timestamp.from(at)).update();
    }

    @Override
    public List<TaxMethodChange> history(int limit) {
        return jdbc.sql("select * from tax_setting_history where key = :k order by changed_at desc, id desc limit :n")
                .param("k", METHOD).param("n", limit)
                .query((rs, n) -> new TaxMethodChange(rs.getString("old_value") == null ? null
                        : TaxMethod.valueOf(rs.getString("old_value")), TaxMethod.valueOf(rs.getString("new_value")),
                        rs.getString("reason"), rs.getString("changed_by"), rs.getTimestamp("changed_at").toInstant()))
                .list();
    }
}
