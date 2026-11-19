package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.pictures.PictureOverrides;
import com.afrotransact.catalog.domain.pictures.PictureProfile;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.catalog.domain.pictures.PictureRulesChange;
import com.afrotransact.catalog.port.PictureRulesRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcPictureRulesRepository implements PictureRulesRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcPictureRulesRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PictureRules> find() {
        return jdbc.sql("select profile, overrides::text as overrides, updated_by, updated_at, version "
                + "from picture_rules_setting where id = 1").query((rs, n) -> {
                    PictureProfile profile = PictureProfile.parse(rs.getString("profile")).orElseThrow();
                    PictureRules base = PictureRules.resolve(profile, read(rs.getString("overrides")));
                    Timestamp at = rs.getTimestamp("updated_at");
                    return base.savedBy(rs.getString("updated_by"), at == null ? null : at.toInstant(),
                            rs.getLong("version"));
                }).optional();
    }

    @Override
    @Transactional
    public PictureRules save(PictureRules next, long expectedVersion, String reason) {
        String overrides = write(next.overrides());
        Timestamp at = Timestamp.from(next.updatedAt());
        Optional<PictureRules> before = find();
        int rows;
        if (expectedVersion == 0) {
            rows = jdbc.sql("""
                    insert into picture_rules_setting (id, profile, overrides, updated_by, updated_at, version)
                    values (1, :profile, cast(:o as jsonb), :by, :at, 1) on conflict (id) do nothing
                    """).param("profile", next.profile().wire()).param("o", overrides).param("by", next.updatedBy())
                    .param("at", at).update();
        } else {
            rows = jdbc.sql("""
                    update picture_rules_setting set profile = :profile, overrides = cast(:o as jsonb), updated_by = :by,
                           updated_at = :at, version = version + 1
                    where id = 1 and version = :version
                    """).param("profile", next.profile().wire()).param("o", overrides).param("by", next.updatedBy())
                    .param("at", at).param("version", expectedVersion).update();
        }
        if (rows == 0) {
            throw new StaleVersionException("The picture rules changed since version " + expectedVersion);
        }
        jdbc.sql("""
                insert into picture_rules_audit (version, previous_profile, profile, overrides, reason, changed_by, changed_at)
                values (:v, :prev, :profile, cast(:o as jsonb), :reason, :by, :at)
                """).param("v", expectedVersion + 1)
                .param("prev", before.map(b -> b.profile().wire()).orElse(null))
                .param("profile", next.profile().wire()).param("o", overrides).param("reason", reason)
                .param("by", next.updatedBy()).param("at", at).update();
        return find().orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PictureRulesChange> history(int limit) {
        return jdbc.sql("select version, previous_profile, profile, overrides::text as overrides, reason, changed_by, "
                + "changed_at from picture_rules_audit order by id desc limit :n").param("n", limit)
                .query((rs, n) -> new PictureRulesChange(rs.getLong("version"),
                        PictureProfile.parse(rs.getString("previous_profile")).orElse(null),
                        PictureProfile.parse(rs.getString("profile")).orElseThrow(),
                        read(rs.getString("overrides")).names(), rs.getString("changed_by"),
                        rs.getTimestamp("changed_at").toInstant(), rs.getString("reason")))
                .list();
    }

    private PictureOverrides read(String text) {
        return text == null || text.isBlank() ? PictureOverrides.none() : json.readValue(text, PictureOverrides.class);
    }

    /** Only the overridden knobs are stored: an absent key keeps the profile's value. */
    private String write(PictureOverrides o) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "minImages", o.minImages());
        put(out, "minLongSidePx", o.minLongSidePx());
        put(out, "minShortSidePx", o.minShortSidePx());
        put(out, "maxMegabytes", o.maxMegabytes());
        put(out, "checkBackground", o.checkBackground());
        put(out, "checkAspect", o.checkAspect());
        put(out, "checkTextOverlay", o.checkTextOverlay());
        put(out, "requireRealPhotoForStorefront", o.requireRealPhotoForStorefront());
        put(out, "requirePhotoForMatching", o.requirePhotoForMatching());
        put(out, "allowPlaceholders", o.allowPlaceholders());
        return json.writeValueAsString(out);
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
