package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.inventory.port.OfferLinkRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOfferLinkRepository implements OfferLinkRepository {

    private final JdbcClient jdbc;

    JdbcOfferLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(UUID offerId, String ownerOrg, String sku, String atin, String status, boolean live,
                       boolean fulfilledByAfroTransact, Instant at) {
        if (!fulfilledByAfroTransact || sku == null) {
            jdbc.sql("delete from fba_offer_link where offer_id = :id").param("id", offerId).update();
            return;
        }
        jdbc.sql("""
                insert into fba_offer_link (offer_id, owner_org, sku, atin, status, live, updated_at)
                values (:id, :owner, :sku, :atin, :status, :live, :at)
                on conflict (offer_id) do update set owner_org = excluded.owner_org, sku = excluded.sku,
                    atin = excluded.atin, status = excluded.status, live = excluded.live,
                    updated_at = excluded.updated_at
                where fba_offer_link.updated_at <= excluded.updated_at
                """)
                .param("id", offerId).param("owner", ownerOrg).param("sku", sku).param("atin", atin)
                .param("status", status).param("live", live).param("at", Timestamp.from(at)).update();
    }
}
