package com.afrotransact.content.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.domain.legal.ConsentRecord;
import com.afrotransact.content.domain.legal.CookieCategory;
import com.afrotransact.content.domain.legal.CookieDefinition;
import com.afrotransact.content.port.CookieRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCookieRepository implements CookieRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcCookieRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<CookieCategory> categories() {
        return jdbc.sql("select key, name, description, required, gpc_opt_out, sort_order from cookie_category order by sort_order, key")
                .query((rs, n) -> new CookieCategory(rs.getString("key"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("required"), rs.getBoolean("gpc_opt_out"),
                        rs.getInt("sort_order")))
                .list();
    }

    @Override
    public List<CookieDefinition> cookies() {
        return jdbc.sql("""
                select d.name, d.provider, d.purpose, d.duration, d.category
                from cookie_definition d join cookie_category c on c.key = d.category
                order by c.sort_order, d.name
                """)
                .query((rs, n) -> new CookieDefinition(rs.getString("name"), rs.getString("provider"),
                        rs.getString("purpose"), rs.getString("duration"), rs.getString("category")))
                .list();
    }

    @Override
    public void saveCategory(CookieCategory c) {
        jdbc.sql("""
                insert into cookie_category (key, name, description, required, gpc_opt_out, sort_order)
                values (:key, :name, :description, :required, :gpc, :order)
                on conflict (key) do update set name = excluded.name, description = excluded.description,
                    required = excluded.required, gpc_opt_out = excluded.gpc_opt_out, sort_order = excluded.sort_order
                """)
                .param("key", c.key()).param("name", c.name()).param("description", c.description())
                .param("required", c.required()).param("gpc", c.gpcOptOut()).param("order", c.order())
                .update();
    }

    @Override
    public void saveCookie(CookieDefinition c) {
        jdbc.sql("""
                insert into cookie_definition (name, provider, purpose, duration, category)
                values (:name, :provider, :purpose, :duration, :category)
                on conflict (name) do update set provider = excluded.provider, purpose = excluded.purpose,
                    duration = excluded.duration, category = excluded.category
                """)
                .param("name", c.name()).param("provider", c.provider()).param("purpose", c.purpose())
                .param("duration", c.duration()).param("category", c.category())
                .update();
    }

    @Override
    public boolean deleteCookie(String name) {
        return jdbc.sql("delete from cookie_definition where name = :name").param("name", name).update() == 1;
    }

    @Override
    public void insertConsent(ConsentRecord r) {
        jdbc.sql("""
                insert into consent_record (id, subject, categories, gpc, policy_version, source, recorded_at)
                values (:id, :subject, cast(:categories as jsonb), :gpc, :version, :source, :at)
                """)
                .param("id", r.id()).param("subject", r.subject()).param("categories", json.write(r.categories()))
                .param("gpc", r.gpc()).param("version", r.policyVersion()).param("source", r.source())
                .param("at", Timestamps.of(r.recordedAt()))
                .update();
    }

    @Override
    public Optional<ConsentRecord> findConsent(UUID id) {
        return jdbc.sql("""
                select id, subject, categories::text as categories, gpc, policy_version, source, recorded_at
                from consent_record where id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new ConsentRecord(rs.getObject("id", UUID.class), rs.getString("subject"),
                        json.booleanMap(rs.getString("categories")), rs.getBoolean("gpc"),
                        rs.getString("policy_version"), rs.getString("source"), Timestamps.instant(rs, "recorded_at")))
                .optional();
    }
}
