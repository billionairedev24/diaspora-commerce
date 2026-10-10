package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.prefill.ProductData;
import com.afrotransact.catalog.port.ProductDataCache;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code product_data_lookup}: provider answers per GTIN-14, upserted on refresh. */
@Repository
class JdbcProductDataCache implements ProductDataCache {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcProductDataCache(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<List<ProductData>> find(Gtin gtin, Instant now) {
        return jdbc.sql("select results::text from product_data_lookup where gtin = :gtin and expires_at > :now")
                .param("gtin", gtin.toGtin14()).param("now", Timestamp.from(now))
                .query(String.class).optional().map(json::productData);
    }

    @Override
    public void store(Gtin gtin, List<ProductData> results, Instant fetchedAt, Instant expiresAt) {
        jdbc.sql("""
                insert into product_data_lookup (gtin, found, results, fetched_at, expires_at)
                values (:gtin, :found, cast(:results as jsonb), :fetchedAt, :expiresAt)
                on conflict (gtin) do update set found = excluded.found, results = excluded.results,
                    fetched_at = excluded.fetched_at, expires_at = excluded.expires_at
                """)
                .param("gtin", gtin.toGtin14())
                .param("found", !results.isEmpty())
                .param("results", json.write(results))
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }
}
