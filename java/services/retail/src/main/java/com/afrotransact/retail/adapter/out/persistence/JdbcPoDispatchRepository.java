package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.domain.DispatchChannel;
import com.afrotransact.retail.domain.DispatchStatus;
import com.afrotransact.retail.domain.PoDispatch;
import com.afrotransact.retail.port.PoDispatchRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPoDispatchRepository implements PoDispatchRepository {

    private final JdbcClient jdbc;

    JdbcPoDispatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertIfAbsent(PoDispatch d) {
        jdbc.sql("""
                insert into po_dispatch (id, po_id, po_number, channel, status, attempts, next_attempt_at, last_error,
                                         recipient, created_at, updated_at, version)
                values (:id, :po, :number, :channel, :status, 0, :next, null, :recipient, :at, :at, 0)
                on conflict (po_id, channel) do nothing
                """)
                .param("id", d.id()).param("po", d.poId()).param("number", d.poNumber())
                .param("channel", d.channel().name()).param("status", d.status().name())
                .param("next", Sql.ts(d.nextAttemptAt())).param("recipient", d.recipient())
                .param("at", Sql.ts(d.createdAt()))
                .update();
    }

    @Override
    public List<PoDispatch> byPo(UUID poId) {
        return jdbc.sql("select * from po_dispatch where po_id = :po order by channel").param("po", poId)
                .query(this::map).list();
    }

    @Override
    public List<UUID> due(Instant now, int limit) {
        return jdbc.sql("""
                select id from po_dispatch where status = 'PENDING' and next_attempt_at <= :now
                order by next_attempt_at limit :limit
                """)
                .param("now", Sql.ts(now)).param("limit", limit)
                .query((rs, n) -> rs.getObject("id", UUID.class)).list();
    }

    @Override
    public Optional<PoDispatch> claim(UUID id) {
        return jdbc.sql("select * from po_dispatch where id = :id and status = 'PENDING' for update skip locked")
                .param("id", id).query(this::map).optional();
    }

    @Override
    public PoDispatch update(PoDispatch d) {
        int rows = jdbc.sql("""
                update po_dispatch set status = :status, attempts = :attempts, next_attempt_at = :next,
                       last_error = :error, recipient = :recipient, asset_id = :asset, link_expires_at = :expires,
                       control_number = :control, transmission_id = :transmission, updated_at = :at,
                       version = version + 1
                where id = :id and version = :version
                """)
                .param("status", d.status().name()).param("attempts", d.attempts())
                .param("next", Sql.ts(d.nextAttemptAt())).param("error", d.lastError())
                .param("recipient", d.recipient()).param("asset", d.assetId())
                .param("expires", Sql.ts(d.linkExpiresAt())).param("control", d.controlNumber())
                .param("transmission", d.transmissionId()).param("at", Sql.ts(d.updatedAt()))
                .param("id", d.id()).param("version", d.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("retail.concurrent-update", "Dispatch " + d.id() + " changed; reload");
        }
        return jdbc.sql("select * from po_dispatch where id = :id").param("id", d.id()).query(this::map).single();
    }

    private PoDispatch map(ResultSet rs, int n) throws SQLException {
        return new PoDispatch(rs.getObject("id", UUID.class), rs.getObject("po_id", UUID.class),
                rs.getString("po_number"), DispatchChannel.valueOf(rs.getString("channel")),
                DispatchStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                Sql.instant(rs, "next_attempt_at"), rs.getString("last_error"), rs.getString("recipient"),
                rs.getObject("asset_id", UUID.class), Sql.instant(rs, "link_expires_at"),
                rs.getString("control_number"), rs.getString("transmission_id"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
