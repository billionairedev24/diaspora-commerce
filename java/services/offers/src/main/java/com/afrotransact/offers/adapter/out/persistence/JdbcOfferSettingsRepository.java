package com.afrotransact.offers.adapter.out.persistence;

import java.sql.Timestamp;
import java.util.Optional;

import com.afrotransact.offers.domain.OfferPolicy;
import com.afrotransact.offers.port.OfferSettingsRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcOfferSettingsRepository implements OfferSettingsRepository {

    private static final String KEY = "policy";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcOfferSettingsRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<OfferPolicy> find() {
        return jdbc.sql("select value::text as value, updated_by, updated_at, version from offer_setting where key = :k")
                .param("k", KEY).query((rs, n) -> {
                    OfferPolicy stored = json.readValue(rs.getString("value"), OfferPolicy.class);
                    Timestamp at = rs.getTimestamp("updated_at");
                    return new OfferPolicy(stored.conditionNoteMaxLength(), stored.packageMaxEdgeIn(),
                            stored.packageMaxWeightLb(), stored.taxSweepBatchSize(), rs.getString("updated_by"),
                            at == null ? null : at.toInstant(), rs.getLong("version"));
                }).optional();
    }

    @Override
    public OfferPolicy save(OfferPolicy p) {
        String value = json.writeValueAsString(new OfferPolicy(p.conditionNoteMaxLength(), p.packageMaxEdgeIn(),
                p.packageMaxWeightLb(), p.taxSweepBatchSize(), null, null, 0));
        Timestamp at = Timestamp.from(p.updatedAt());
        int rows;
        if (p.version() == 0) {
            rows = jdbc.sql("""
                    insert into offer_setting (key, value, updated_by, updated_at, version)
                    values (:k, cast(:v as jsonb), :by, :at, 1) on conflict (key) do nothing
                    """).param("k", KEY).param("v", value).param("by", p.updatedBy()).param("at", at).update();
        } else {
            rows = jdbc.sql("""
                    update offer_setting set value = cast(:v as jsonb), updated_by = :by, updated_at = :at,
                           version = version + 1
                    where key = :k and version = :version
                    """).param("k", KEY).param("v", value).param("by", p.updatedBy()).param("at", at)
                    .param("version", p.version()).update();
        }
        if (rows == 0) {
            throw new ConflictException("offers.concurrent-update", "The settings were changed by someone else; reload and try again");
        }
        return find().orElseThrow();
    }
}
