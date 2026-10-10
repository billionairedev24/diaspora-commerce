package com.afrotransact.ai.adapter.out.persistence;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.afrotransact.ai.domain.lola.ProductCard;
import com.afrotransact.ai.port.ConversationStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Lola memory in PostgreSQL; product cards of assistant turns are kept as jsonb. */
@Repository
class JdbcConversationStore implements ConversationStore {

    private static final TypeReference<List<ProductCard>> CARDS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcConversationStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Turn> recent(String sessionId, String subject, int limit) {
        List<Turn> newestFirst = jdbc.sql("""
                select role, content, products::text as products, created_at from lola_message
                where session_id = :session and subject = :subject
                order by id desc
                limit :limit
                """)
                .param("session", sessionId)
                .param("subject", subject)
                .param("limit", Math.max(limit, 0))
                .query((rs, n) -> new Turn(rs.getString("role"), rs.getString("content"),
                        json.readValue(rs.getString("products"), CARDS), rs.getTimestamp("created_at").toInstant()))
                .list();
        List<Turn> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    @Override
    public void append(String sessionId, String subject, List<Turn> turns) {
        for (Turn t : turns) {
            jdbc.sql("""
                    insert into lola_message (session_id, subject, role, content, products, created_at)
                    values (:session, :subject, :role, :content, cast(:products as jsonb), :at)
                    """)
                    .param("session", sessionId)
                    .param("subject", subject)
                    .param("role", t.role())
                    .param("content", t.content())
                    .param("products", json.writeValueAsString(t.products()))
                    .param("at", Timestamp.from(t.at()))
                    .update();
        }
    }
}
