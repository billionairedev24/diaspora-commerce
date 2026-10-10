package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.OrgSuspension;
import com.afrotransact.seller.domain.SuspensionReason;
import com.afrotransact.seller.port.OrgSuspensionRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrgSuspensionRepository implements OrgSuspensionRepository {

    private final JdbcClient jdbc;

    JdbcOrgSuspensionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OrgSuspension insert(OrgSuspension s) {
        jdbc.sql("""
                insert into org_suspension (id, org_id, reason_code, note, actor_id, previous_status, suspended_at,
                                            reinstated_at, reinstated_by, reinstate_note, version)
                values (:id, :org, :reason, :note, :actor, :previous, :suspendedAt, :reinstatedAt, :reinstatedBy,
                        :reinstateNote, 0)
                """)
                .param("id", s.id()).param("org", s.orgId()).param("reason", s.reason().name())
                .param("note", s.note()).param("actor", s.actorId()).param("previous", s.previousStatus().name())
                .param("suspendedAt", Sql.ts(s.suspendedAt())).param("reinstatedAt", Sql.ts(s.reinstatedAt()))
                .param("reinstatedBy", s.reinstatedBy()).param("reinstateNote", s.reinstateNote())
                .update();
        return find(s.id()).orElseThrow();
    }

    @Override
    public OrgSuspension update(OrgSuspension s) {
        int rows = jdbc.sql("""
                update org_suspension set reinstated_at = :reinstatedAt, reinstated_by = :reinstatedBy,
                       reinstate_note = :reinstateNote, version = version + 1
                where id = :id and version = :version
                """)
                .param("reinstatedAt", Sql.ts(s.reinstatedAt())).param("reinstatedBy", s.reinstatedBy())
                .param("reinstateNote", s.reinstateNote()).param("id", s.id()).param("version", s.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "Suspension changed; reload and retry");
        }
        return find(s.id()).orElseThrow();
    }

    @Override
    public Optional<OrgSuspension> findActive(String orgId) {
        return jdbc.sql("select * from org_suspension where org_id = :org and reinstated_at is null")
                .param("org", orgId).query(JdbcOrgSuspensionRepository::map).optional();
    }

    @Override
    public List<OrgSuspension> history(String orgId, int limit) {
        return jdbc.sql("select * from org_suspension where org_id = :org order by suspended_at desc limit :limit")
                .param("org", orgId).param("limit", limit).query(JdbcOrgSuspensionRepository::map).list();
    }

    private Optional<OrgSuspension> find(UUID id) {
        return jdbc.sql("select * from org_suspension where id = :id").param("id", id)
                .query(JdbcOrgSuspensionRepository::map).optional();
    }

    private static OrgSuspension map(ResultSet rs, int row) throws SQLException {
        return new OrgSuspension(rs.getObject("id", UUID.class), rs.getString("org_id"),
                SuspensionReason.valueOf(rs.getString("reason_code")), rs.getString("note"),
                rs.getString("actor_id"), OrgStatus.valueOf(rs.getString("previous_status")),
                Sql.instant(rs, "suspended_at"), Sql.instant(rs, "reinstated_at"), rs.getString("reinstated_by"),
                rs.getString("reinstate_note"), rs.getLong("version"));
    }
}
