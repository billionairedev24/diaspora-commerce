package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.StaffInvitation;
import com.afrotransact.identity.port.StaffInvitationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStaffInvitationRepository implements StaffInvitationRepository {

    private final JdbcClient jdbc;

    JdbcStaffInvitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(StaffInvitation i) {
        jdbc.sql("""
                insert into staff_invitation (id, email, first_name, last_name, role_key, reason, token_hash, status,
                    invited_by, created_at, expires_at, last_sent_at, send_count)
                values (:id, :email, :first, :last, :role, :reason, :hash, :status, :by, :createdAt, :expiresAt,
                    :sentAt, :sendCount)
                """)
                .param("id", i.id())
                .param("email", i.email())
                .param("first", i.firstName())
                .param("last", i.lastName())
                .param("role", i.roleKey())
                .param("reason", i.reason())
                .param("hash", i.tokenHash())
                .param("status", i.status().name())
                .param("by", i.invitedBy())
                .param("createdAt", Jdbc.ts(i.createdAt()))
                .param("expiresAt", Jdbc.ts(i.expiresAt()))
                .param("sentAt", Jdbc.ts(i.lastSentAt()))
                .param("sendCount", i.sendCount())
                .update();
    }

    @Override
    public Optional<StaffInvitation> findById(UUID id) {
        return jdbc.sql("select * from staff_invitation where id = :id").param("id", id)
                .query(JdbcStaffInvitationRepository::map).optional();
    }

    @Override
    public Optional<StaffInvitation> findByTokenHash(String tokenHash) {
        return jdbc.sql("select * from staff_invitation where token_hash = :h").param("h", tokenHash)
                .query(JdbcStaffInvitationRepository::map).optional();
    }

    @Override
    public Optional<StaffInvitation> findPendingByEmail(String email) {
        return jdbc.sql("select * from staff_invitation where lower(email) = lower(:e) and status = 'PENDING'")
                .param("e", email).query(JdbcStaffInvitationRepository::map).optional();
    }

    @Override
    public boolean reissue(StaffInvitation i) {
        return jdbc.sql("""
                update staff_invitation set token_hash = :hash, expires_at = :expiresAt, last_sent_at = :sentAt,
                    send_count = :sendCount, version = version + 1
                where id = :id and status = 'PENDING'
                """)
                .param("id", i.id())
                .param("hash", i.tokenHash())
                .param("expiresAt", Jdbc.ts(i.expiresAt()))
                .param("sentAt", Jdbc.ts(i.lastSentAt()))
                .param("sendCount", i.sendCount())
                .update() == 1;
    }

    @Override
    public boolean revoke(StaffInvitation i) {
        return jdbc.sql("""
                update staff_invitation set status = 'REVOKED', revoked_at = :at, revoked_by = :by, version = version + 1
                where id = :id and status = 'PENDING'
                """)
                .param("id", i.id())
                .param("at", Jdbc.ts(i.revokedAt()))
                .param("by", i.revokedBy())
                .update() == 1;
    }

    @Override
    public boolean claim(UUID id, UUID userId, Instant now) {
        return jdbc.sql("""
                update staff_invitation set status = 'ACCEPTED', accepted_at = :now, accepted_user = :user,
                    version = version + 1
                where id = :id and status = 'PENDING' and expires_at > :now
                """)
                .param("id", id)
                .param("user", userId)
                .param("now", Jdbc.ts(now))
                .update() == 1;
    }

    @Override
    public List<StaffInvitation> open(int limit, int offset) {
        return jdbc.sql("select * from staff_invitation where status = 'PENDING' order by created_at desc, id "
                + "limit :limit offset :offset")
                .param("limit", limit).param("offset", offset)
                .query(JdbcStaffInvitationRepository::map).list();
    }

    @Override
    public long countOpen() {
        return jdbc.sql("select count(*) from staff_invitation where status = 'PENDING'")
                .query(Long.class).single();
    }

    private static StaffInvitation map(ResultSet rs, int n) throws SQLException {
        return new StaffInvitation(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("role_key"), rs.getString("reason"),
                rs.getString("token_hash"), InvitationStatus.valueOf(rs.getString("status")),
                rs.getString("invited_by"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at"),
                Jdbc.instant(rs, "last_sent_at"), rs.getInt("send_count"), Jdbc.instant(rs, "accepted_at"),
                rs.getObject("accepted_user", UUID.class), Jdbc.instant(rs, "revoked_at"),
                rs.getString("revoked_by"));
    }
}
