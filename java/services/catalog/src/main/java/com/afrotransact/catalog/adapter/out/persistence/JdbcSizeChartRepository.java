package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSizeChartRepository implements SizeChartRepository {

    private static final String SELECT = """
            select id, owner_org, name, unit_system, product_type_code, category_id, columns::text as columns,
                   rows::text as rows, fit_note, how_to_measure, retired_at, created_at, updated_at, version
            from size_chart
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSizeChartRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public SizeChart insert(SizeChart c, String createdBy) {
        jdbc.sql("""
                insert into size_chart (id, owner_org, name, unit_system, product_type_code, category_id, columns, rows,
                                        fit_note, how_to_measure, retired_at, created_by, created_at, updated_at, version)
                values (:id, :org, :name, :unit, :type, :category, cast(:columns as jsonb), cast(:rows as jsonb),
                        :fit, :howTo, :retiredAt, :by, :createdAt, :updatedAt, 0)
                """)
                .param("id", c.id()).param("org", c.ownerOrg()).param("name", c.name())
                .param("unit", c.unitSystem().name()).param("type", c.productTypeCode())
                .param("category", c.categoryId()).param("columns", json.write(c.columns()))
                .param("rows", json.write(c.rows())).param("fit", c.fitNote() == null ? null : c.fitNote().name())
                .param("howTo", c.howToMeasure()).param("retiredAt", timestamp(c))
                .param("by", createdBy).param("createdAt", Timestamp.from(c.createdAt()))
                .param("updatedAt", Timestamp.from(c.updatedAt()))
                .update();
        return findById(c.id()).orElseThrow();
    }

    @Override
    public SizeChart update(SizeChart c) {
        int rows = jdbc.sql("""
                update size_chart set name = :name, unit_system = :unit, product_type_code = :type,
                       category_id = :category, columns = cast(:columns as jsonb), rows = cast(:rows as jsonb),
                       fit_note = :fit, how_to_measure = :howTo, retired_at = :retiredAt, updated_at = :updatedAt,
                       version = version + 1
                where id = :id and version = :version
                """)
                .param("id", c.id()).param("name", c.name()).param("unit", c.unitSystem().name())
                .param("type", c.productTypeCode()).param("category", c.categoryId())
                .param("columns", json.write(c.columns())).param("rows", json.write(c.rows()))
                .param("fit", c.fitNote() == null ? null : c.fitNote().name()).param("howTo", c.howToMeasure())
                .param("retiredAt", timestamp(c)).param("updatedAt", Timestamp.from(c.updatedAt()))
                .param("version", c.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("catalog.size-chart-changed",
                    "This size chart was changed since you opened it; reload it and try again");
        }
        return findById(c.id()).orElseThrow();
    }

    @Override
    public Optional<SizeChart> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<SizeChart> findActiveByName(String ownerOrg, String name) {
        return jdbc.sql(SELECT + " where owner_org = :org and lower(name) = lower(:name) and retired_at is null")
                .param("org", ownerOrg).param("name", name.strip()).query(this::map).optional();
    }

    @Override
    public Map<String, SizeChart> activeByNames(String ownerOrg, Collection<String> names) {
        Map<String, SizeChart> out = new HashMap<>();
        if (names.isEmpty()) {
            return out;
        }
        List<String> lower = names.stream().map(n -> n.strip().toLowerCase(Locale.ROOT)).distinct().toList();
        jdbc.sql(SELECT + " where owner_org = :org and lower(name) in (:names) and retired_at is null")
                .param("org", ownerOrg).param("names", lower).query(this::map).list()
                .forEach(c -> out.put(c.name().toLowerCase(Locale.ROOT), c));
        return out;
    }

    @Override
    public ChartPage list(ChartQuery q) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (q.ownerOrg() != null) {
            where.append(" and owner_org = :org");
            params.put("org", q.ownerOrg());
        }
        if (!q.includeRetired()) {
            where.append(" and retired_at is null");
        }
        if (q.productTypeCode() != null) {
            where.append(" and (product_type_code is null or product_type_code = :type)");
            params.put("type", q.productTypeCode());
        }
        if (q.categoryId() != null) {
            where.append(" and (category_id is null or category_id = :category)");
            params.put("category", q.categoryId());
        }
        if (q.text() != null && !q.text().isBlank()) {
            where.append(" and name ilike :text");
            params.put("text", "%" + q.text().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        long total = jdbc.sql("select count(*) from size_chart" + where).params(params).query(Long.class).single();
        params.put("limit", q.size());
        params.put("offset", (long) q.page() * q.size());
        List<SizeChart> items = jdbc.sql(SELECT + where + " order by updated_at desc, id limit :limit offset :offset")
                .params(params).query(this::map).list();
        return new ChartPage(items, total, q.page(), q.size());
    }

    private static Timestamp timestamp(SizeChart c) {
        return c.retiredAt() == null ? null : Timestamp.from(c.retiredAt());
    }

    private SizeChart map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp retired = rs.getTimestamp("retired_at");
        String fit = rs.getString("fit_note");
        return new SizeChart(rs.getObject("id", UUID.class), rs.getString("owner_org"), rs.getString("name"),
                MeasurementUnit.valueOf(rs.getString("unit_system")), rs.getString("product_type_code"),
                rs.getObject("category_id", UUID.class), json.chartColumns(rs.getString("columns")),
                json.chartRows(rs.getString("rows")), fit == null ? null : FitNote.valueOf(fit),
                rs.getString("how_to_measure"), retired == null ? null : retired.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
