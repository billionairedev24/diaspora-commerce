package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;

import com.afrotransact.catalog.port.MatchDecisionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMatchDecisionRepository implements MatchDecisionRepository {

    private final JdbcClient jdbc;

    JdbcMatchDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Decision d) {
        jdbc.sql("""
                insert into product_match_decision (id, seller_org_id, choice, query, category_id, atin, shown,
                                                    likely_duplicate_of, review_state, decided_at)
                values (:id, :org, :choice, :query, :category, :atin, :shown, :likely, :state, :at)
                """)
                .param("id", d.id()).param("org", d.sellerOrgId()).param("choice", d.choice()).param("query", d.query())
                .param("category", d.categoryId()).param("atin", d.atin()).param("shown", d.shown())
                .param("likely", d.likelyDuplicateOf()).param("state", d.reviewState())
                .param("at", Timestamp.from(d.decidedAt())).update();
    }

    @Override
    public long countNoneSince(String sellerOrgId, Instant since) {
        return jdbc.sql("""
                select count(*) from product_match_decision
                where seller_org_id = :org and choice = 'NONE_OF_THESE' and decided_at >= :since
                """).param("org", sellerOrgId).param("since", Timestamp.from(since)).query(Long.class).single();
    }
}
