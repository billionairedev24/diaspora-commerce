package com.afrotransact.catalog.adapter.out.persistence;

import java.util.List;

import com.afrotransact.catalog.port.BakeryClaimRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBakeryClaimRepository implements BakeryClaimRepository {

    private final JdbcClient jdbc;

    JdbcBakeryClaimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> organisations() {
        return jdbc.sql("""
                select distinct owner_org from product
                where owner_kind = 'SELLER'
                  and lower(coalesce(listing->'sellerBehaviour'->>'seller_is_bakery', '')) in ('yes', 'true', 'y', '1')
                order by owner_org
                """).query(String.class).list();
    }
}
