package com.afrotransact.bookings.adapter.out.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOfferingRepository implements OfferingRepository {

    private static final String SELECT = """
            select o.*, array(select m.member_id from offering_member m where m.offering_id = o.id) as members
            from offering o
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcOfferingRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ServiceOffering insert(ServiceOffering o) {
        jdbc.sql("""
                insert into offering (id, org_id, name, description, category, duration_minutes, price_minor,
                                      deposit_minor, currency, modes, add_ons, active, created_at, updated_at, version)
                values (:id, :org, :name, :description, :category, :duration, :price, :deposit, :currency,
                        cast(:modes as jsonb), cast(:addOns as jsonb), :active, :createdAt, :updatedAt, 0)
                """)
                .param("id", o.id())
                .param("org", o.orgId())
                .param("name", o.name())
                .param("description", o.description())
                .param("category", o.category())
                .param("duration", o.durationMinutes())
                .param("price", o.priceMinor())
                .param("deposit", o.depositMinor())
                .param("currency", o.currency())
                .param("modes", json.write(o.modes()))
                .param("addOns", json.write(o.addOns()))
                .param("active", o.active())
                .param("createdAt", Sql.ts(o.createdAt()))
                .param("updatedAt", Sql.ts(o.updatedAt()))
                .update();
        replaceMembers(o);
        return find(o.id()).orElseThrow();
    }

    @Override
    public ServiceOffering update(ServiceOffering o) {
        int rows = jdbc.sql("""
                update offering set name = :name, description = :description, category = :category,
                       duration_minutes = :duration, price_minor = :price, deposit_minor = :deposit,
                       modes = cast(:modes as jsonb), add_ons = cast(:addOns as jsonb), active = :active,
                       updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("name", o.name())
                .param("description", o.description())
                .param("category", o.category())
                .param("duration", o.durationMinutes())
                .param("price", o.priceMinor())
                .param("deposit", o.depositMinor())
                .param("modes", json.write(o.modes()))
                .param("addOns", json.write(o.addOns()))
                .param("active", o.active())
                .param("updatedAt", Sql.ts(o.updatedAt()))
                .param("id", o.id())
                .param("version", o.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("bookings.concurrent-update", "Service changed; reload and retry");
        }
        replaceMembers(o);
        return find(o.id()).orElseThrow();
    }

    private void replaceMembers(ServiceOffering o) {
        jdbc.sql("delete from offering_member where offering_id = :id").param("id", o.id()).update();
        for (UUID m : o.memberIds()) {
            jdbc.sql("insert into offering_member (offering_id, member_id) values (:o, :m)")
                    .param("o", o.id()).param("m", m).update();
        }
    }

    @Override
    public Optional<ServiceOffering> find(UUID id) {
        return jdbc.sql(SELECT + " where o.id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public List<ServiceOffering> byOrg(String orgId, boolean activeOnly) {
        return jdbc.sql(SELECT + " where o.org_id = :org" + (activeOnly ? " and o.active" : "")
                        + " order by o.created_at, o.id")
                .param("org", orgId).query(this::map).list();
    }

    @Override
    public List<ServiceOffering> search(String text, String category, Set<String> orgIds, int limit) {
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" join provider p on p.org_id = o.org_id where o.active and not exists (select 1 from "
                        + "org_visibility v where v.org_id = o.org_id and v.hidden)");
        boolean hasText = text != null && !text.isBlank();
        boolean hasCategory = category != null && !category.isBlank();
        if (hasText) {
            sql.append(" and (o.name ilike :q or o.description ilike :q or o.category ilike :q"
                    + " or p.display_name ilike :q)");
        }
        if (hasCategory) {
            sql.append(" and o.category = :category");
        }
        if (orgIds != null) {
            sql.append(orgIds.isEmpty() ? " and o.modes @> cast(:virtual as jsonb)"
                    : " and (o.org_id in (:orgs) or o.modes @> cast(:virtual as jsonb))");
        }
        sql.append(" order by o.name, o.id limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (hasText) {
            spec = spec.param("q", "%" + text.trim().replace("%", "") + "%");
        }
        if (hasCategory) {
            spec = spec.param("category", category.trim().toLowerCase(Locale.ROOT));
        }
        if (orgIds != null) {
            spec = spec.param("virtual", "[\"VIRTUAL\"]");
            if (!orgIds.isEmpty()) {
                spec = spec.param("orgs", List.copyOf(orgIds));
            }
        }
        return spec.query(this::map).list();
    }

    private ServiceOffering map(ResultSet rs, int rowNum) throws SQLException {
        Set<UUID> members = new HashSet<>();
        Array array = rs.getArray("members");
        if (array != null) {
            for (Object v : (Object[]) array.getArray()) {
                members.add((UUID) v);
            }
        }
        return new ServiceOffering(rs.getObject("id", UUID.class), rs.getString("org_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("category"), rs.getInt("duration_minutes"),
                rs.getLong("price_minor"), rs.getLong("deposit_minor"), rs.getString("currency"),
                json.modes(rs.getString("modes")), json.addOns(rs.getString("add_ons")), members,
                rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }
}
