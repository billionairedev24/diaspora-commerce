package com.afrotransact.seller.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.port.ReasonCodeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReasonCodeRepository implements ReasonCodeRepository {

    private final JdbcClient jdbc;

    JdbcReasonCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReasonCode> findAll() {
        return jdbc.sql("select code, label, applies_to from review_reason_code order by sort_order")
                .query((rs, n) -> new ReasonCode(rs.getString("code"), rs.getString("label"),
                        rs.getString("applies_to"))).list();
    }

    @Override
    public Optional<ReasonCode> find(String code) {
        return jdbc.sql("select code, label, applies_to from review_reason_code where code = :c").param("c", code)
                .query((rs, n) -> new ReasonCode(rs.getString("code"), rs.getString("label"),
                        rs.getString("applies_to"))).optional();
    }
}
