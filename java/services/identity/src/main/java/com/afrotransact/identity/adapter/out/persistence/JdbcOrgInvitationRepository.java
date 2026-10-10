package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.OrgInvitation;
import com.afrotransact.identity.port.OrgInvitationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrgInvitationRepository implements OrgInvitationRepository {

    private final JdbcClient jdbc;

    JdbcOrgInvitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(OrgInvitation i) {
        jdbc.sql("""
                insert into org_invitation (id, org_id, email, role_key, token_hash, status, invited_by, created_at,
                    expires_at)
                values (:id, :org, :email, :role, :hash, :status, :by, :createdAt, :expiresAt)
                """)
                .param("id", i.id())
                .param("org", i.orgId())
                .param("email", i.email())
                .param("role", i.roleKey())
                .param("hash", i.tokenHash())
                .param("status", i.status().name())
                .param("by", i.invitedBy())
                .param("createdAt", Jdbc.ts(i.createdAt()))
                .param("expiresAt", Jdbc.ts(i.expiresAt()))
                .update();
    }

    @Override
    public void update(OrgInvitation i) {
        jdbc.sql("""
                update org_invitation set status = :status, accepted_at = :acceptedAt, accepted_by = :acceptedBy,
                    revoked_at = :revokedAt, revoked_by = :revokedBy
                where id = :id
                """)
                .param("id", i.id())
                .param("status", i.status().name())
                .param("acceptedAt", Jdbc.ts(i.acceptedAt()))
                .param("acceptedBy", i.acceptedBy())
                .param("revokedAt", Jdbc.ts(i.revokedAt()))
                .param("revokedBy", i.revokedBy())
                .update();
    }

    @Override
    public Optional<OrgInvitation> findById(UUID id) {
        return jdbc.sql("select * from org_invitation where id = :id").param("id", id)
                .query(JdbcOrgInvitationRepository::map).optional();
    }

    @Override
    public Optional<OrgInvitation> findByTokenHash(String tokenHash) {
        return jdbc.sql("select * from org_invitation where token_hash = :h").param("h", tokenHash)
                .query(JdbcOrgInvitationRepository::map).optional();
    }

    @Override
    public Optional<OrgInvitation> findPending(String orgId, String email) {
        return jdbc.sql("""
                select * from org_invitation where org_id = :org and lower(email) = lower(:email) and status = 'PENDING'
                """)
                .param("org", orgId).param("email", email)
                .query(JdbcOrgInvitationRepository::map).optional();
    }

    @Override
    public List<OrgInvitation> pending(String orgId, Instant now) {
        return jdbc.sql("""
                select * from org_invitation where org_id = :org and status = 'PENDING' and expires_at > :now
                order by created_at desc, id
                """)
                .param("org", orgId).param("now", Jdbc.ts(now))
                .query(JdbcOrgInvitationRepository::map).list();
    }

    private static OrgInvitation map(ResultSet rs, int n) throws SQLException {
        return new OrgInvitation(rs.getObject("id", UUID.class), rs.getString("org_id"), rs.getString("email"),
                rs.getString("role_key"), rs.getString("token_hash"), InvitationStatus.valueOf(rs.getString("status")),
                rs.getString("invited_by"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at"),
                Jdbc.instant(rs, "accepted_at"), rs.getObject("accepted_by", UUID.class),
                Jdbc.instant(rs, "revoked_at"), rs.getString("revoked_by"));
    }
}
