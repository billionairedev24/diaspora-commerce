package com.afrotransact.offers.adapter.out.persistence;

import java.sql.Timestamp;

import com.afrotransact.offers.port.OfferAuditRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOfferAuditRepository implements OfferAuditRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcOfferAuditRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void record(Entry e) {
        jdbc.sql("""
                insert into offer_change_audit (id, offer_id, seller_org, atin, action, source, import_id, row_key, actor,
                                                on_behalf_of, changes, at)
                values (:id, :offerId, :org, :atin, :action, :source, :importId, :rowKey, :actor, :onBehalfOf,
                        cast(:changes as jsonb), :at)
                """)
                .param("id", e.id()).param("offerId", e.offerId()).param("org", e.sellerOrg()).param("atin", e.atin())
                .param("action", e.action()).param("source", e.source()).param("importId", e.importId())
                .param("rowKey", e.rowKey()).param("actor", e.actor()).param("onBehalfOf", e.onBehalfOf())
                .param("changes", json.write(e.changes())).param("at", Timestamp.from(e.at())).update();
    }
}
