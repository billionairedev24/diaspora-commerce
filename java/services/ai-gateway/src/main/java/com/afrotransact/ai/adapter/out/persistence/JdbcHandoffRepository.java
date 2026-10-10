package com.afrotransact.ai.adapter.out.persistence;

import java.sql.Timestamp;

import com.afrotransact.ai.port.HandoffRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHandoffRepository implements HandoffRepository {

    private final JdbcClient jdbc;

    JdbcHandoffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Handoff h) {
        jdbc.sql("""
                insert into ai_handoff (id, session_id, subject, reason, summary, created_at)
                values (:id, :session, :subject, :reason, :summary, :at)
                """)
                .param("id", h.id())
                .param("session", h.sessionId())
                .param("subject", h.subject())
                .param("reason", h.reason())
                .param("summary", h.summary())
                .param("at", Timestamp.from(h.at()))
                .update();
    }
}
