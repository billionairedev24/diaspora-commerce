package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportRow;
import com.afrotransact.inventory.domain.StockImportStatus;
import com.afrotransact.inventory.domain.StockRowMessage;
import com.afrotransact.inventory.domain.StockRowOutcome;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.StockImportRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcStockImportRepository implements StockImportRepository {

    private static final TypeReference<List<StockRowMessage>> MESSAGES = new TypeReference<>() { };
    private static final int BATCH = 500;

    private static final String SELECT = """
            select id, reference, owner_org, uploaded_by, file_name, file_sha256, same_file_as, mode, header::text as header,
                   status, total_rows, valid_rows, warning_rows, error_rows, processed_rows, applied_rows,
                   unchanged_rows, failed_rows, created_at, started_at, finished_at, heartbeat_at, version
            from stock_import
            """;
    private static final String ROW_SELECT = """
            select row_number, sku, node_id, lot_no, quantity, expiry, status, messages::text as messages,
                   cells::text as cells, outcome, outcome_note, qty_before, qty_after
            from stock_import_row
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcStockImportRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(StockImport u, List<StockImportRow> rows) {
        jdbc.sql("""
                insert into stock_import (id, reference, owner_org, uploaded_by, file_name, file_sha256, same_file_as, mode,
                       header, status, total_rows, valid_rows, warning_rows, error_rows, created_at, version)
                values (:id, :reference, :org, :by, :file, :sha, :same, :mode, cast(:header as jsonb), :status, :total,
                        :valid, :warning, :error, :createdAt, 0)
                """)
                .param("id", u.id()).param("reference", u.reference()).param("org", u.ownerOrg())
                .param("by", u.uploadedBy()).param("file", u.fileName()).param("sha", u.fileSha256())
                .param("same", u.sameFileAs()).param("mode", u.mode().name())
                .param("header", json.write(u.header())).param("status", u.status().name())
                .param("total", u.totalRows()).param("valid", u.validRows()).param("warning", u.warningRows())
                .param("error", u.errorRows()).param("createdAt", Timestamp.from(u.createdAt())).update();
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<StockImportRow> batch = rows.subList(from, Math.min(rows.size(), from + BATCH));
            StringBuilder sql = new StringBuilder("""
                    insert into stock_import_row (import_id, row_number, sku, node_id, lot_no, quantity, expiry, status,
                                                  messages, cells)
                    values
                    """);
            Map<String, Object> params = new HashMap<>();
            params.put("importId", u.id());
            for (int i = 0; i < batch.size(); i++) {
                StockImportRow r = batch.get(i);
                sql.append(i == 0 ? "" : ",").append("(:importId, :n").append(i).append(", :s").append(i)
                        .append(", :nd").append(i).append(", :l").append(i).append(", :q").append(i).append(", :e")
                        .append(i).append(", :st").append(i).append(", cast(:m").append(i).append(" as jsonb), cast(:c")
                        .append(i).append(" as jsonb))");
                params.put("n" + i, r.rowNumber());
                params.put("s" + i, cut(r.sku(), 60));
                params.put("nd" + i, cut(r.nodeId(), 20));
                params.put("l" + i, r.lotNo());
                params.put("q" + i, r.quantity());
                params.put("e" + i, r.expiry() == null ? null : Date.valueOf(r.expiry()));
                params.put("st" + i, r.status());
                params.put("m" + i, json.write(r.messages()));
                params.put("c" + i, json.write(r.cells()));
            }
            jdbc.sql(sql.toString()).params(params).update();
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    @Override
    public Optional<StockImport> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<StockImport> lock(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public StockImport update(StockImport u) {
        int n = jdbc.sql("""
                update stock_import set status = :status, processed_rows = :processed, applied_rows = :applied,
                       unchanged_rows = :unchanged, failed_rows = :failed, started_at = :startedAt,
                       finished_at = :finishedAt, heartbeat_at = :heartbeatAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("id", u.id()).param("status", u.status().name()).param("processed", u.processedRows())
                .param("applied", u.appliedRows()).param("unchanged", u.unchangedRows())
                .param("failed", u.failedRows()).param("startedAt", ts(u.startedAt()))
                .param("finishedAt", ts(u.finishedAt())).param("heartbeatAt", ts(u.heartbeatAt()))
                .param("version", u.version()).update();
        if (n == 0) {
            throw new ConflictException("inventory.import-changed", "This import changed meanwhile; reload it");
        }
        return findById(u.id()).orElseThrow();
    }

    @Override
    public Page<StockImport> list(String ownerOrg, int page, int size) {
        long total = jdbc.sql("select count(*) from stock_import where owner_org = :org").param("org", ownerOrg)
                .query(Long.class).single();
        List<StockImport> items = jdbc.sql(SELECT + " where owner_org = :org order by created_at desc limit :l offset :o")
                .param("org", ownerOrg).param("l", size).param("o", (long) page * size).query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    @Override
    public Page<StockImportRow> rows(UUID importId, String status, StockRowOutcome outcome, int page, int size) {
        StringBuilder where = new StringBuilder(" where import_id = :id");
        Map<String, Object> params = new HashMap<>();
        params.put("id", importId);
        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }
        if (outcome != null) {
            where.append(" and outcome = :outcome");
            params.put("outcome", outcome.name());
        }
        long total = jdbc.sql("select count(*) from stock_import_row" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<StockImportRow> items = jdbc.sql(ROW_SELECT + where + " order by row_number limit :limit offset :offset")
                .params(params).query(this::mapRow).list();
        return new Page<>(items, total, page, size);
    }

    @Override
    public List<StockImportRow> allRows(UUID importId) {
        return jdbc.sql(ROW_SELECT + " where import_id = :id order by row_number").param("id", importId)
                .query(this::mapRow).list();
    }

    @Override
    public List<StockImportRow> pendingRows(UUID importId, int limit) {
        return jdbc.sql(ROW_SELECT + " where import_id = :id and outcome = 'PENDING' and status <> 'ERROR' "
                + "order by row_number limit :limit").param("id", importId).param("limit", limit).query(this::mapRow)
                .list();
    }

    @Override
    public Map<StockRowOutcome, Long> outcomeCounts(UUID importId) {
        Map<StockRowOutcome, Long> out = new EnumMap<>(StockRowOutcome.class);
        jdbc.sql("select outcome, count(*) as n from stock_import_row where import_id = :id group by outcome")
                .param("id", importId).query((rs, n) -> {
                    out.put(StockRowOutcome.valueOf(rs.getString("outcome")), rs.getLong("n"));
                    return 0;
                }).list();
        return out;
    }

    @Override
    public int setOutcome(UUID importId, int rowNumber, StockRowOutcome outcome, String note, Integer before,
                          Integer after) {
        return jdbc.sql("""
                update stock_import_row set outcome = :outcome, outcome_note = :note, qty_before = :before,
                       qty_after = :after
                where import_id = :id and row_number = :row and outcome = 'PENDING'
                """)
                .param("id", importId).param("row", rowNumber).param("outcome", outcome.name()).param("note", note)
                .param("before", before).param("after", after).update();
    }

    @Override
    public int skipErrorRows(UUID importId) {
        return jdbc.sql("""
                update stock_import_row set outcome = 'SKIPPED', outcome_note = 'Not imported: the row has errors'
                where import_id = :id and status = 'ERROR' and outcome = 'PENDING'
                """).param("id", importId).update();
    }

    @Override
    public Optional<StockImport> findLatestByHash(String ownerOrg, StockImportMode mode, String fileSha256) {
        return jdbc.sql(SELECT + " where owner_org = :org and mode = :mode and file_sha256 = :sha "
                + "order by created_at desc limit 1").param("org", ownerOrg).param("mode", mode.name())
                .param("sha", fileSha256).query(this::map).optional();
    }

    @Override
    public Optional<StockImport> findStartedByHash(String ownerOrg, StockImportMode mode, String fileSha256, UUID exceptId) {
        return jdbc.sql(SELECT + " where owner_org = :org and mode = :mode and file_sha256 = :sha and id <> :id "
                + "and started_at is not null order by started_at desc limit 1").param("org", ownerOrg)
                .param("mode", mode.name()).param("sha", fileSha256).param("id", exceptId).query(this::map).optional();
    }

    @Override
    public boolean hasActive(String ownerOrg) {
        return jdbc.sql("select count(*) from stock_import where owner_org = :org and status in ('QUEUED', 'RUNNING')")
                .param("org", ownerOrg).query(Integer.class).single() > 0;
    }

    @Override
    public List<UUID> stalled(Instant before, int limit) {
        return jdbc.sql("""
                select id from stock_import
                where status in ('QUEUED', 'RUNNING') and coalesce(heartbeat_at, created_at) < :before
                order by coalesce(heartbeat_at, created_at) limit :limit
                """).param("before", Timestamp.from(before)).param("limit", limit).query(UUID.class).list();
    }

    @Override
    public String nextReference() {
        long n = jdbc.sql("select nextval('stock_import_seq')").query(Long.class).single();
        return String.format("SI-%06d", n);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private StockImport map(ResultSet rs, int n) throws SQLException {
        return new StockImport(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("owner_org"),
                rs.getString("uploaded_by"), rs.getString("file_name"), rs.getString("file_sha256"),
                rs.getObject("same_file_as", UUID.class), StockImportMode.valueOf(rs.getString("mode")),
                json.strings(rs.getString("header")), StockImportStatus.valueOf(rs.getString("status")),
                rs.getInt("total_rows"), rs.getInt("valid_rows"), rs.getInt("warning_rows"), rs.getInt("error_rows"),
                rs.getInt("processed_rows"), rs.getInt("applied_rows"), rs.getInt("unchanged_rows"),
                rs.getInt("failed_rows"), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "finished_at"), instant(rs, "heartbeat_at"), rs.getLong("version"));
    }

    private StockImportRow mapRow(ResultSet rs, int n) throws SQLException {
        Date expiry = rs.getDate("expiry");
        int qty = rs.getInt("quantity");
        boolean noQty = rs.wasNull();
        int before = rs.getInt("qty_before");
        boolean noBefore = rs.wasNull();
        int after = rs.getInt("qty_after");
        boolean noAfter = rs.wasNull();
        return new StockImportRow(rs.getInt("row_number"), rs.getString("sku"), rs.getString("node_id"),
                rs.getString("lot_no"), noQty ? null : qty, expiry == null ? null : expiry.toLocalDate(),
                rs.getString("status"), json.read(rs.getString("messages"), MESSAGES), json.strings(rs.getString("cells")),
                StockRowOutcome.valueOf(rs.getString("outcome")), rs.getString("outcome_note"), noBefore ? null : before,
                noAfter ? null : after);
    }
}
