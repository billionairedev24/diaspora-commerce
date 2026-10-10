package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierContact;
import com.afrotransact.retail.domain.SupplierScorecard;
import com.afrotransact.retail.domain.SupplierStatus;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.SupplierRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcSupplierRepository implements SupplierRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<SupplierContact>> CONTACTS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSupplierRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Supplier insert(Supplier s) {
        jdbc.sql("""
                insert into supplier (id, code, name, country, city, currency, payment_terms, lead_time_days,
                                      certifications, contacts, status, status_reason, sc_receipts, sc_on_time,
                                      sc_units, sc_defective, created_at, updated_at, version)
                values (:id, :code, :name, :country, :city, :currency, :terms, :lead, cast(:certs as jsonb),
                        cast(:contacts as jsonb), :status, :reason, :scR, :scO, :scU, :scD, :createdAt, :updatedAt, 0)
                """)
                .params(params(s)).param("id", s.id()).param("code", s.code()).param("country", s.country())
                .param("createdAt", Sql.ts(s.createdAt()))
                .update();
        return findById(s.id()).orElseThrow();
    }

    @Override
    public Supplier update(Supplier s) {
        int rows = jdbc.sql("""
                update supplier set name = :name, city = :city, currency = :currency, payment_terms = :terms,
                       lead_time_days = :lead, certifications = cast(:certs as jsonb),
                       contacts = cast(:contacts as jsonb), status = :status, status_reason = :reason,
                       sc_receipts = :scR, sc_on_time = :scO, sc_units = :scU, sc_defective = :scD,
                       updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .params(params(s)).param("id", s.id()).param("version", s.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("retail.concurrent-update", "Supplier " + s.code() + " changed; reload");
        }
        return findById(s.id()).orElseThrow();
    }

    @Override
    public Optional<Supplier> findById(UUID id) {
        return jdbc.sql("select * from supplier where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Supplier> findByCode(String code) {
        return jdbc.sql("select * from supplier where code = :code").param("code", code).query(this::map).optional();
    }

    @Override
    public List<Supplier> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from supplier where id in (:ids)").param("ids", ids).query(this::map).list();
    }

    @Override
    public Page<Supplier> page(SupplierStatus status, String query, int page, int size) {
        Sql.Where where = new Sql.Where().eq("status", "status", Sql.name(status))
                .raw("(lower(name) like :q or lower(code) like :q)", "q",
                        query == null || query.isBlank() ? null : "%" + query.toLowerCase(Locale.ROOT).trim() + "%");
        long total = jdbc.sql("select count(*) from supplier" + where.sql()).params(where.params())
                .query(Long.class).single();
        List<Supplier> items = jdbc.sql("select * from supplier" + where.sql()
                        + " order by name limit :limit offset :offset")
                .params(where.params()).param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    private Map<String, Object> params(Supplier s) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", s.name());
        p.put("city", s.city());
        p.put("currency", s.currency());
        p.put("terms", s.paymentTerms());
        p.put("lead", s.leadTimeDays());
        p.put("certs", json.write(s.certifications()));
        p.put("contacts", json.write(s.contacts()));
        p.put("status", s.status().name());
        p.put("reason", s.statusReason());
        p.put("scR", s.scorecard().receipts());
        p.put("scO", s.scorecard().onTimeReceipts());
        p.put("scU", s.scorecard().unitsReceived());
        p.put("scD", s.scorecard().unitsDefective());
        p.put("updatedAt", Sql.ts(s.updatedAt()));
        return p;
    }

    private Supplier map(ResultSet rs, int rowNum) throws SQLException {
        return new Supplier(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("country"), rs.getString("city"), rs.getString("currency"),
                rs.getString("payment_terms"), rs.getInt("lead_time_days"),
                json.list(rs.getString("certifications"), STRINGS), json.list(rs.getString("contacts"), CONTACTS),
                SupplierStatus.valueOf(rs.getString("status")), rs.getString("status_reason"),
                new SupplierScorecard(rs.getInt("sc_receipts"), rs.getInt("sc_on_time"), rs.getLong("sc_units"),
                        rs.getLong("sc_defective")),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
