package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.domain.EmailAddress;
import com.afrotransact.content.domain.privacy.PrivacyRequest;
import com.afrotransact.content.domain.privacy.PrivacyRequestStatus;
import com.afrotransact.content.domain.privacy.PrivacyRequestType;
import com.afrotransact.content.port.PrivacyRequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPrivacyRequestRepository implements PrivacyRequestRepository {

    private static final String COLUMNS = """
            id, reference, request_type, email, state_code, details, account_subject, locale, status, resolution_note,
            submitted_at, due_at, resolved_at, resolved_by, version
            """;

    private final JdbcClient jdbc;

    JdbcPrivacyRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextSequence() {
        return jdbc.sql("select nextval('privacy_request_seq')").query(Long.class).single();
    }

    @Override
    public void insert(PrivacyRequest r) {
        jdbc.sql("""
                insert into privacy_request (id, reference, request_type, email, state_code, details, account_subject,
                    locale, status, resolution_note, submitted_at, due_at, resolved_at, resolved_by, version)
                values (:id, :ref, :type, :email, :state, :details, :account, :locale, :status, :note, :submitted, :due,
                    :resolved, :by, 0)
                """)
                .param("id", r.id()).param("ref", r.reference()).param("type", r.type().name())
                .param("email", r.email().value()).param("state", r.stateCode()).param("details", r.details())
                .param("account", r.accountSubject().orElse(null)).param("locale", r.locale())
                .param("status", r.status().name()).param("note", r.resolutionNote())
                .param("submitted", Timestamps.of(r.submittedAt())).param("due", Timestamps.of(r.dueAt()))
                .param("resolved", Timestamps.of(r.resolvedAt())).param("by", r.resolvedBy())
                .update();
    }

    @Override
    public Optional<PrivacyRequest> findByReference(String reference) {
        return jdbc.sql("select " + COLUMNS + " from privacy_request where reference = :ref")
                .param("ref", reference).query(this::map).optional();
    }

    @Override
    public List<PrivacyRequest> list(PrivacyRequestStatus status, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                 from privacy_request
                where (cast(:status as varchar) is null or status = :status)
                order by submitted_at desc limit :limit
                """)
                .param("status", status == null ? null : status.name()).param("limit", limit)
                .query(this::map).list();
    }

    @Override
    public boolean update(PrivacyRequest r) {
        return jdbc.sql("""
                update privacy_request set status = :status, resolution_note = :note, resolved_at = :resolved,
                    resolved_by = :by, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", r.status().name()).param("note", r.resolutionNote())
                .param("resolved", Timestamps.of(r.resolvedAt())).param("by", r.resolvedBy())
                .param("id", r.id()).param("version", r.version())
                .update() == 1;
    }

    private PrivacyRequest map(ResultSet rs, int row) throws SQLException {
        return new PrivacyRequest(rs.getObject("id", UUID.class), rs.getString("reference"),
                PrivacyRequestType.valueOf(rs.getString("request_type")), new EmailAddress(rs.getString("email")),
                rs.getString("state_code"), rs.getString("details"), Optional.ofNullable(rs.getString("account_subject")),
                rs.getString("locale"), PrivacyRequestStatus.valueOf(rs.getString("status")),
                rs.getString("resolution_note"), Timestamps.instant(rs, "submitted_at"), Timestamps.instant(rs, "due_at"),
                Timestamps.instant(rs, "resolved_at"), rs.getString("resolved_by"), rs.getLong("version"));
    }
}
