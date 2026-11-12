package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.util.Optional;

import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.port.CatalogSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcCatalogSettingsRepository implements CatalogSettingsRepository {

    private static final String KEY = "policy";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcCatalogSettingsRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<CatalogPolicy> find() {
        return jdbc.sql("select value::text as value, updated_by, updated_at, version from catalog_setting where key = :k")
                .param("k", KEY).query((rs, n) -> {
                    CatalogPolicy stored = json.readValue(rs.getString("value"), CatalogPolicy.class);
                    Timestamp at = rs.getTimestamp("updated_at");
                    return new CatalogPolicy(stored.reservedPrefixes(), stored.mpnAttributeKey(),
                            stored.reviewSlaBusinessDays(), stored.alertAfterBusinessDays(), stored.noteMaxLength(),
                            stored.knownGs1BrandMinPages(), stored.exemptionGatedCategoryCodes(),
                            stored.exemptionRequiresApprovalForKnownBrands(), rs.getString("updated_by"),
                            at == null ? null : at.toInstant(), rs.getLong("version"));
                }).optional();
    }

    @Override
    public CatalogPolicy save(CatalogPolicy p) {
        String value = json.writeValueAsString(new CatalogPolicy(p.reservedPrefixes(), p.mpnAttributeKey(),
                p.reviewSlaBusinessDays(), p.alertAfterBusinessDays(), p.noteMaxLength(), p.knownGs1BrandMinPages(),
                p.exemptionGatedCategoryCodes(), p.exemptionRequiresApprovalForKnownBrands(), null, null, 0));
        Timestamp at = Timestamp.from(p.updatedAt());
        int rows;
        if (p.version() == 0) {
            rows = jdbc.sql("""
                    insert into catalog_setting (key, value, updated_by, updated_at, version)
                    values (:k, cast(:v as jsonb), :by, :at, 1) on conflict (key) do nothing
                    """).param("k", KEY).param("v", value).param("by", p.updatedBy()).param("at", at).update();
        } else {
            rows = jdbc.sql("""
                    update catalog_setting set value = cast(:v as jsonb), updated_by = :by, updated_at = :at,
                           version = version + 1
                    where key = :k and version = :version
                    """).param("k", KEY).param("v", value).param("by", p.updatedBy()).param("at", at)
                    .param("version", p.version()).update();
        }
        if (rows == 0) {
            throw new StaleVersionException("The settings changed since version " + p.version());
        }
        return find().orElseThrow();
    }
}
