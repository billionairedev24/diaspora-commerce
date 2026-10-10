package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductTakedown;
import com.afrotransact.catalog.port.ProductTakedownRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProductTakedownRepository implements ProductTakedownRepository {

    private final JdbcClient jdbc;

    JdbcProductTakedownRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProductTakedown insert(ProductTakedown t) {
        jdbc.sql("""
                insert into product_takedown (id, product_id, reason_code, note, actor_id, previous_status,
                                              taken_down_at, restored_at, restored_by, restore_note, version)
                values (:id, :product, :reason, :note, :actor, :previous, :takenDownAt, :restoredAt, :restoredBy,
                        :restoreNote, 0)
                """)
                .param("id", t.id()).param("product", t.productId()).param("reason", t.reasonCode().name())
                .param("note", t.note()).param("actor", t.actorId()).param("previous", t.previousStatus().name())
                .param("takenDownAt", ts(t.takenDownAt())).param("restoredAt", ts(t.restoredAt()))
                .param("restoredBy", t.restoredBy()).param("restoreNote", t.restoreNote())
                .update();
        return find(t.id()).orElseThrow();
    }

    @Override
    public ProductTakedown update(ProductTakedown t) {
        int rows = jdbc.sql("""
                update product_takedown set restored_at = :restoredAt, restored_by = :restoredBy,
                       restore_note = :restoreNote, version = version + 1
                where id = :id and version = :version
                """)
                .param("restoredAt", ts(t.restoredAt())).param("restoredBy", t.restoredBy())
                .param("restoreNote", t.restoreNote()).param("id", t.id()).param("version", t.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("catalog.concurrent-update", "The takedown changed; reload and retry");
        }
        return find(t.id()).orElseThrow();
    }

    @Override
    public Optional<ProductTakedown> findActive(UUID productId) {
        return jdbc.sql("select * from product_takedown where product_id = :p and restored_at is null")
                .param("p", productId).query(JdbcProductTakedownRepository::map).optional();
    }

    private Optional<ProductTakedown> find(UUID id) {
        return jdbc.sql("select * from product_takedown where id = :id").param("id", id)
                .query(JdbcProductTakedownRepository::map).optional();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static ProductTakedown map(ResultSet rs, int row) throws SQLException {
        return new ProductTakedown(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                ListingReasonCode.valueOf(rs.getString("reason_code")), rs.getString("note"),
                rs.getString("actor_id"), ProductStatus.valueOf(rs.getString("previous_status")),
                instant(rs, "taken_down_at"), instant(rs, "restored_at"), rs.getString("restored_by"),
                rs.getString("restore_note"), rs.getLong("version"));
    }
}
