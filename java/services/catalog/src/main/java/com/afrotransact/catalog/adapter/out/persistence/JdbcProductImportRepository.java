package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportStatus;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowMessage;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.domain.imports.ValidatedRow;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcProductImportRepository implements ProductImportRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<RowMessage>> MESSAGES = new TypeReference<>() { };
    private static final int BATCH = 500;

    private static final String SELECT = """
            select id, reference, owner_kind, owner_org, uploaded_by, uploaded_by_name, file_name, file_format, mode,
                   product_type, header::text as header, file_warnings::text as file_warnings, status, valid_only,
                   total_rows, valid_rows, warning_rows, error_rows, product_count, processed_rows, created_products,
                   updated_products, failed_rows, notify, created_at, started_at, finished_at, heartbeat_at, version
            from product_import
            """;
    private static final String ROW_SELECT = """
            select row_number, group_key, sku, status, messages::text as messages, cells::text as cells, outcome,
                   outcome_note, product_id
            from product_import_row
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcProductImportRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(ProductImport u, List<ValidatedRow> rows) {
        jdbc.sql("""
                insert into product_import (id, reference, owner_kind, owner_org, uploaded_by, uploaded_by_name,
                       file_name, file_format, mode, product_type, header, file_warnings, status, valid_only,
                       total_rows, valid_rows, warning_rows, error_rows, product_count, notify, created_at, version)
                values (:id, :reference, :ownerKind, :ownerOrg, :uploadedBy, :uploadedByName, :fileName, :format,
                        :mode, :productType, cast(:header as jsonb), cast(:warnings as jsonb), :status, false,
                        :total, :valid, :warning, :error, :products, :notify, :createdAt, 0)
                """)
                .param("id", u.id()).param("reference", u.reference()).param("ownerKind", u.owner().kind().name())
                .param("ownerOrg", u.owner().orgId()).param("uploadedBy", u.uploadedBy())
                .param("uploadedByName", u.uploadedByName()).param("fileName", u.fileName())
                .param("format", u.format().name()).param("mode", u.mode().name())
                .param("productType", u.productTypeCode()).param("header", json.writeValueAsString(u.header()))
                .param("warnings", json.writeValueAsString(u.fileWarnings())).param("status", u.status().name())
                .param("total", u.totalRows()).param("valid", u.validRows()).param("warning", u.warningRows())
                .param("error", u.errorRows()).param("products", u.productCount()).param("notify", u.notifySeller())
                .param("createdAt", Timestamp.from(u.createdAt()))
                .update();
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<ValidatedRow> batch = rows.subList(from, Math.min(rows.size(), from + BATCH));
            StringBuilder sql = new StringBuilder("""
                    insert into product_import_row (import_id, row_number, group_key, sku, status, messages, cells)
                    values
                    """);
            Map<String, Object> params = new HashMap<>();
            params.put("importId", u.id());
            for (int i = 0; i < batch.size(); i++) {
                ValidatedRow r = batch.get(i);
                sql.append(i == 0 ? "" : ",").append("(:importId, :n").append(i).append(", :g").append(i)
                        .append(", :s").append(i).append(", :st").append(i).append(", cast(:m").append(i)
                        .append(" as jsonb), cast(:c").append(i).append(" as jsonb))");
                params.put("n" + i, r.rowNumber());
                params.put("g" + i, r.groupKey());
                params.put("s" + i, r.sku().length() > 60 ? r.sku().substring(0, 60) : r.sku());
                params.put("st" + i, r.status().name());
                params.put("m" + i, json.writeValueAsString(r.messages()));
                params.put("c" + i, json.writeValueAsString(r.cells()));
            }
            jdbc.sql(sql.toString()).params(params).update();
        }
    }

    @Override
    public Optional<ProductImport> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<ProductImport> lock(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public ProductImport update(ProductImport u) {
        int n = jdbc.sql("""
                update product_import set status = :status, valid_only = :validOnly, processed_rows = :processed,
                       created_products = :created, updated_products = :updated, failed_rows = :failed,
                       started_at = :startedAt, finished_at = :finishedAt, heartbeat_at = :heartbeatAt,
                       version = version + 1
                where id = :id and version = :version
                """)
                .param("id", u.id()).param("status", u.status().name()).param("validOnly", u.validOnly())
                .param("processed", u.processedRows()).param("created", u.createdProducts())
                .param("updated", u.updatedProducts()).param("failed", u.failedRows())
                .param("startedAt", ts(u.startedAt())).param("finishedAt", ts(u.finishedAt()))
                .param("heartbeatAt", ts(u.heartbeatAt())).param("version", u.version())
                .update();
        if (n == 0) {
            throw new ConflictException("catalog.import-changed", "This upload changed meanwhile; reload it");
        }
        return findById(u.id()).orElseThrow();
    }

    @Override
    public Page<ProductImport> list(String orgId, int page, int size) {
        long total = jdbc.sql("select count(*) from product_import where owner_org = :org").param("org", orgId)
                .query(Long.class).single();
        List<ProductImport> items = jdbc.sql(SELECT + " where owner_org = :org order by created_at desc limit :limit offset :offset")
                .param("org", orgId).param("limit", size).param("offset", (long) page * size).query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    @Override
    public Page<StoredRow> rows(UUID importId, RowStatus status, RowOutcome outcome, int page, int size) {
        StringBuilder where = new StringBuilder(" where import_id = :id");
        Map<String, Object> params = new HashMap<>();
        params.put("id", importId);
        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status.name());
        }
        if (outcome != null) {
            where.append(" and outcome = :outcome");
            params.put("outcome", outcome.name());
        }
        long total = jdbc.sql("select count(*) from product_import_row" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<StoredRow> items = jdbc.sql(ROW_SELECT + where + " order by row_number limit :limit offset :offset")
                .params(params).query(this::mapRow).list();
        return new Page<>(items, total, page, size);
    }

    @Override
    public List<StoredRow> allRows(UUID importId) {
        return jdbc.sql(ROW_SELECT + " where import_id = :id order by row_number").param("id", importId)
                .query(this::mapRow).list();
    }

    @Override
    public List<String> pendingGroups(UUID importId, int limit) {
        return jdbc.sql("""
                select group_key from product_import_row
                where import_id = :id and outcome = 'PENDING' and status <> 'ERROR'
                group by group_key order by min(row_number) limit :limit
                """).param("id", importId).param("limit", limit).query(String.class).list();
    }

    @Override
    public List<StoredRow> group(UUID importId, String groupKey) {
        return jdbc.sql(ROW_SELECT + " where import_id = :id and group_key = :g order by row_number")
                .param("id", importId).param("g", groupKey).query(this::mapRow).list();
    }

    @Override
    public void setOutcome(UUID importId, Collection<Integer> rowNumbers, RowOutcome outcome, String note,
                           UUID productId) {
        if (rowNumbers.isEmpty()) {
            return;
        }
        jdbc.sql("""
                update product_import_row set outcome = :outcome, outcome_note = :note, product_id = :productId
                where import_id = :id and row_number in (:rows)
                """)
                .param("id", importId).param("rows", new ArrayList<>(rowNumbers)).param("outcome", outcome.name())
                .param("note", note).param("productId", productId)
                .update();
    }

    @Override
    public int skipErrorRows(UUID importId) {
        return jdbc.sql("""
                update product_import_row set outcome = 'SKIPPED', outcome_note = 'Not imported: the row has errors'
                where import_id = :id and status = 'ERROR' and outcome = 'PENDING'
                """).param("id", importId).update();
    }

    @Override
    public List<UUID> stalled(Instant before, int limit) {
        return jdbc.sql("""
                select id from product_import
                where status in ('QUEUED', 'RUNNING') and coalesce(heartbeat_at, created_at) < :before
                order by coalesce(heartbeat_at, created_at) limit :limit
                """).param("before", Timestamp.from(before)).param("limit", limit).query(UUID.class).list();
    }

    @Override
    public String nextReference() {
        long n = jdbc.sql("select nextval('product_import_seq')").query(Long.class).single();
        return String.format("BU-%06d", n);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private ProductImport map(ResultSet rs, int rowNum) throws SQLException {
        return new ProductImport(rs.getObject("id", UUID.class), rs.getString("reference"),
                new ListingOwner(ListingOwnerKind.valueOf(rs.getString("owner_kind")), rs.getString("owner_org")),
                rs.getString("uploaded_by"), rs.getString("uploaded_by_name"), rs.getString("file_name"),
                SpreadsheetFormat.valueOf(rs.getString("file_format")), ImportMode.valueOf(rs.getString("mode")),
                rs.getString("product_type"), json.readValue(rs.getString("header"), STRINGS),
                json.readValue(rs.getString("file_warnings"), STRINGS), ImportStatus.valueOf(rs.getString("status")),
                rs.getBoolean("valid_only"), rs.getInt("total_rows"), rs.getInt("valid_rows"),
                rs.getInt("warning_rows"), rs.getInt("error_rows"), rs.getInt("product_count"),
                rs.getInt("processed_rows"), rs.getInt("created_products"), rs.getInt("updated_products"),
                rs.getInt("failed_rows"), rs.getBoolean("notify"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), instant(rs, "heartbeat_at"),
                rs.getLong("version"));
    }

    private StoredRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StoredRow(rs.getInt("row_number"), rs.getString("group_key"), rs.getString("sku"),
                RowStatus.valueOf(rs.getString("status")), json.readValue(rs.getString("messages"), MESSAGES),
                json.readValue(rs.getString("cells"), STRINGS), RowOutcome.valueOf(rs.getString("outcome")),
                rs.getString("outcome_note"), rs.getObject("product_id", UUID.class));
    }
}
