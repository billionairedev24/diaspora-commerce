package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.CycleCount;
import com.afrotransact.inventory.domain.CycleCountLine;
import com.afrotransact.inventory.domain.CycleCountReason;
import com.afrotransact.inventory.domain.CycleCountStatus;
import com.afrotransact.inventory.port.CycleCountRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCycleCountRepository implements CycleCountRepository {

    private final JdbcClient jdbc;

    JdbcCycleCountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextNumber() {
        long n = jdbc.sql("select nextval('cycle_count_seq')").query(Long.class).single();
        return "CC-" + String.format("%06d", n);
    }

    @Override
    public void insert(CycleCount c) {
        jdbc.sql("""
                insert into cycle_count (id, number, node_id, sku, bin_prefix, owner_org, blind, status, note,
                    created_by, created_at, counted_at, posted_by, posted_at, updated_at, version)
                values (:id, :number, :node, :sku, :bin, :owner, :blind, :status, :note, :createdBy, :createdAt,
                    :countedAt, :postedBy, :postedAt, :updatedAt, 0)
                """).params(params(c)).update();
        writeLines(c);
    }

    @Override
    public CycleCount update(CycleCount c) {
        Map<String, Object> p = params(c);
        p.put("version", c.version());
        int rows = jdbc.sql("""
                update cycle_count set status = :status, counted_at = :countedAt, posted_by = :postedBy,
                    posted_at = :postedAt, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """).params(p).update();
        if (rows == 0) {
            throw new ConflictException("inventory.concurrent-update", "Cycle count " + c.number()
                    + " changed meanwhile; reload and retry");
        }
        jdbc.sql("delete from cycle_count_line where count_id = :id").param("id", c.id()).update();
        writeLines(c);
        return findById(c.id()).orElseThrow();
    }

    @Override
    public Optional<CycleCount> findById(UUID id) {
        return jdbc.sql("select * from cycle_count where id = :id").param("id", id).query(this::map).optional()
                .map(this::withLines);
    }

    @Override
    public Optional<CycleCount> lockById(UUID id) {
        return jdbc.sql("select * from cycle_count where id = :id for update").param("id", id).query(this::map)
                .optional().map(this::withLines);
    }

    @Override
    public Page<CycleCount> search(CountQuery q) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (q.nodeId() != null) {
            where.append(" and c.node_id = :node");
            p.put("node", q.nodeId());
        }
        if (q.status() != null) {
            where.append(" and c.status = :status");
            p.put("status", q.status().name());
        }
        if (q.ownerOrg() != null) {
            where.append(" and c.owner_org = :owner");
            p.put("owner", q.ownerOrg());
        }
        if (q.sku() != null) {
            where.append(" and (c.sku = :sku or exists (select 1 from cycle_count_line l where l.count_id = c.id"
                    + " and l.sku = :sku))");
            p.put("sku", q.sku());
        }
        long total = jdbc.sql("select count(*) from cycle_count c" + where).params(p).query(Long.class).single();
        p.put("limit", q.size());
        p.put("offset", (long) q.page() * q.size());
        List<CycleCount> items = jdbc.sql("select c.* from cycle_count c" + where
                        + " order by c.created_at desc, c.id limit :limit offset :offset")
                .params(p).query(this::map).list().stream().map(this::withLines).toList();
        return new Page<>(items, total, q.page(), q.size());
    }

    private void writeLines(CycleCount c) {
        for (CycleCountLine l : c.lines()) {
            jdbc.sql("""
                    insert into cycle_count_line (count_id, line_no, lot_id, sku, lot_no, bin, exp_date, expected_qty,
                        counted_qty, reason_code, note, counted_by, counted_at, posted_delta)
                    values (:id, :lineNo, :lotId, :sku, :lotNo, :bin, :exp, :expected, :counted, :reason, :note,
                        :countedBy, :countedAt, :delta)
                    """)
                    .param("id", c.id()).param("lineNo", l.lineNo()).param("lotId", l.lotId()).param("sku", l.sku())
                    .param("lotNo", l.lotNo()).param("bin", l.bin()).param("exp", l.expDate())
                    .param("expected", l.expectedQty()).param("counted", l.countedQty())
                    .param("reason", l.reasonCode() == null ? null : l.reasonCode().name()).param("note", l.note())
                    .param("countedBy", l.countedBy()).param("countedAt", ts(l.countedAt()))
                    .param("delta", l.postedDelta()).update();
        }
    }

    private CycleCount withLines(CycleCount c) {
        List<CycleCountLine> lines = jdbc.sql("select * from cycle_count_line where count_id = :id order by line_no")
                .param("id", c.id()).query((rs, n) -> new CycleCountLine(rs.getInt("line_no"),
                        rs.getObject("lot_id", UUID.class), rs.getString("sku"), rs.getString("lot_no"),
                        rs.getString("bin"), rs.getObject("exp_date", LocalDate.class), rs.getInt("expected_qty"),
                        rs.getObject("counted_qty", Integer.class), reason(rs.getString("reason_code")),
                        rs.getString("note"), rs.getString("counted_by"), instant(rs.getTimestamp("counted_at")),
                        rs.getObject("posted_delta", Integer.class))).list();
        return new CycleCount(c.id(), c.number(), c.nodeId(), c.sku(), c.binPrefix(), c.ownerOrg(), c.blind(),
                c.status(), c.note(), lines, c.createdBy(), c.createdAt(), c.countedAt(), c.postedBy(), c.postedAt(),
                c.updatedAt(), c.version());
    }

    private CycleCount map(ResultSet rs, int n) throws SQLException {
        return new CycleCount(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("node_id"),
                rs.getString("sku"), rs.getString("bin_prefix"), rs.getString("owner_org"), rs.getBoolean("blind"),
                CycleCountStatus.valueOf(rs.getString("status")), rs.getString("note"), List.of(),
                rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("counted_at")), rs.getString("posted_by"),
                instant(rs.getTimestamp("posted_at")), instant(rs.getTimestamp("updated_at")), rs.getLong("version"));
    }

    private static Map<String, Object> params(CycleCount c) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", c.id());
        p.put("number", c.number());
        p.put("node", c.nodeId());
        p.put("sku", c.sku());
        p.put("bin", c.binPrefix());
        p.put("owner", c.ownerOrg());
        p.put("blind", c.blind());
        p.put("status", c.status().name());
        p.put("note", c.note());
        p.put("createdBy", c.createdBy());
        p.put("createdAt", ts(c.createdAt()));
        p.put("countedAt", ts(c.countedAt()));
        p.put("postedBy", c.postedBy());
        p.put("postedAt", ts(c.postedAt()));
        p.put("updatedAt", ts(c.updatedAt()));
        return p;
    }

    private static CycleCountReason reason(String v) {
        return v == null ? null : CycleCountReason.valueOf(v);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
