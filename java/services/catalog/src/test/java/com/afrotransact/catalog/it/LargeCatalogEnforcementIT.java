package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.ListingEnforcement;
import com.afrotransact.catalog.application.ListingVisibilityService;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S216: hide / restore of a large seller catalog in pages of 500, resumable and idempotent.
 *
 * <p>Sized at a few thousand products (7 pages, the last one partial) rather than the 50k of the acceptance criteria:
 * every page is the same constant-size transaction (keyset on {@code (owner_org, id)}, variants by
 * {@code product_variant (product_id)}, 500 outbox rows), so 50k products is 100 such pages and nothing in a page grows
 * with the catalog size. Seeding 50k products with variants plus 50k outbox rows per action in a Testcontainers
 * PostgreSQL would add minutes and hundreds of MB to every CI run without exercising anything new; the test logs the
 * per-page time so the 50k duration can be extrapolated.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1", "at.catalog.listing-enforcement.resume-interval=1h"})
@ActiveProfiles("local")
@Testcontainers
class LargeCatalogEnforcementIT {

    private static final String CONSUMER = "catalog.seller-compliance";
    private static final Logger LOG = LoggerFactory.getLogger(LargeCatalogEnforcementIT.class);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private final ListingVisibilityService visibility;
    private final InboxDeduplicator inbox;
    private final JdbcClient jdbc;

    @Autowired
    LargeCatalogEnforcementIT(ListingVisibilityService visibility, InboxDeduplicator inbox, JdbcClient jdbc) {
        this.visibility = visibility;
        this.inbox = inbox;
        this.jdbc = jdbc;
    }

    @Test
    void hidesAndRestoresALargeCatalogInPagesOf500() {
        String org = "org_large_a";
        seed(org, "LA", 3_250);
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        UUID hideId = UUID.randomUUID();

        long started = System.nanoTime();
        assertThat(visibility.hide(new ListingEnforcement(hideId, org, "AUTO_PAST_DUE", t0))).isEqualTo(3_250);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        LOG.info("S216 hide of 3,250 products: {} ms ({} ms per 500-product page)", took.toMillis(),
                took.toMillis() / 7);

        assertThat(run(hideId)).containsExactly("DONE", 3_250, 7);
        assertThat(visibility.hidden(org)).isTrue();
        assertThat(eventCount("catalog.product.hidden", org)).isEqualTo(3_250);
        assertThat(distinctProducts("catalog.product.hidden", org)).isEqualTo(3_250);

        assertThat(visibility.hide(new ListingEnforcement(hideId, org, "AUTO_PAST_DUE", t0)))
                .as("redelivery of a finished run publishes nothing").isZero();
        assertThat(eventCount("catalog.product.hidden", org)).isEqualTo(3_250);

        UUID restoreId = UUID.randomUUID();
        assertThat(visibility.restore(new ListingEnforcement(restoreId, org, "CLEARED", t0.plusSeconds(60))))
                .isEqualTo(3_250);
        assertThat(run(restoreId)).containsExactly("DONE", 3_250, 7);
        assertThat(visibility.hidden(org)).isFalse();
        assertThat(distinctProducts("catalog.product.restored", org)).isEqualTo(3_250);
    }

    @Test
    void redeliveryAfterACrashResumesAfterTheLastCommittedPage() {
        String org = "org_large_b";
        seed(org, "LB", 1_200);
        UUID eventId = UUID.randomUUID();
        crashedHideAfter(org, eventId, 500, Instant.now());

        int published = visibility.hide(new ListingEnforcement(eventId, org, "AUTO_PAST_DUE",
                Instant.parse("2026-10-01T10:00:00Z")));

        assertThat(published).as("only the 700 products after the cursor").isEqualTo(700);
        assertThat(run(eventId)).containsExactly("DONE", 1_200, 3);
        assertThat(distinctProducts("catalog.product.hidden", org)).isEqualTo(700);
        assertThat(firstPublishedIdAfterCursor(org)).isTrue();
    }

    @Test
    void stalledRunsAreResumedByTheBackgroundJob() {
        String org = "org_large_c";
        seed(org, "LC", 1_100);
        UUID eventId = UUID.randomUUID();
        crashedHideAfter(org, eventId, 1_000, Instant.now().minus(Duration.ofMinutes(10)));

        assertThat(visibility.resumeStalled()).isGreaterThanOrEqualTo(100);
        assertThat(run(eventId)).containsExactly("DONE", 1_100, 3);
        assertThat(eventCount("catalog.product.hidden", org)).isEqualTo(100);
    }

    @Test
    void aNewerDecisionSupersedesARunInProgress() {
        String org = "org_large_d";
        seed(org, "LD", 800);
        UUID hideId = UUID.randomUUID();
        crashedHideAfter(org, hideId, 500, Instant.now());

        UUID restoreId = UUID.randomUUID();
        assertThat(visibility.restore(new ListingEnforcement(restoreId, org, "CLEARED",
                Instant.parse("2026-10-02T10:00:00Z")))).isEqualTo(800);

        assertThat(run(hideId).getFirst()).isEqualTo("SUPERSEDED");
        assertThat(visibility.hide(new ListingEnforcement(hideId, org, "AUTO_PAST_DUE",
                Instant.parse("2026-10-01T10:00:00Z")))).as("superseded run is not resumed").isZero();
        assertThat(eventCount("catalog.product.hidden", org)).isZero();
        assertThat(visibility.hidden(org)).isFalse();
    }

    /**
     * State a pod leaves when it dies after committing {@code done} products of a hide: decision recorded, inbox
     * marked, run RUNNING with its cursor on the last committed product (the crafted run published nothing itself, so
     * events counted afterwards are exactly what the resume published).
     */
    private void crashedHideAfter(String org, UUID eventId, int done, Instant lastPage) {
        Instant decided = Instant.parse("2026-10-01T10:00:00Z");
        jdbc.sql("""
                insert into org_listing_suppression (org_id, hidden, reason, changed_at, updated_at)
                values (:org, true, 'AUTO_PAST_DUE', :at, :at)
                """).param("org", org).param("at", Timestamp.from(decided)).update();
        assertThat(inbox.firstDelivery(CONSUMER, eventId)).isTrue();
        UUID cursor = jdbc.sql("select id from product where owner_org = :org order by id offset :n limit 1")
                .param("org", org).param("n", done - 1).query(UUID.class).single();
        jdbc.sql("""
                insert into listing_enforcement_run (event_id, org_id, action, reason, status, cursor_product_id,
                    processed, pages, started_at, updated_at)
                values (:id, :org, 'HIDE', 'AUTO_PAST_DUE', 'RUNNING', :cursor, :done, :pages, :at, :at)
                """).param("id", eventId).param("org", org).param("cursor", cursor).param("done", done)
                .param("pages", done / 500).param("at", Timestamp.from(lastPage)).update();
    }

    /** No product at or before the crash cursor was republished (Postgres uuid order, as the keyset uses). */
    private boolean firstPublishedIdAfterCursor(String org) {
        UUID cursor = jdbc.sql("select id from product where owner_org = :org order by id offset 499 limit 1")
                .param("org", org).query(UUID.class).single();
        long atOrBefore = jdbc.sql("""
                select count(*) from platform_outbox o join product p on p.id::text = o.aggregate_id
                where o.type = 'catalog.product.hidden' and p.owner_org = :org and p.id <= :cursor
                """).param("org", org).param("cursor", cursor).query(Long.class).single();
        return atOrBefore == 0;
    }

    private void seed(String org, String prefix, int count) {
        jdbc.sql("""
                insert into product (id, atin, owner_kind, owner_org, product_type, title, attributes, images,
                                     variation_theme, status, created_at, updated_at, version)
                select gen_random_uuid(), 'AT' || :prefix || lpad(n::text, 6, '0'), 'SELLER', :org,
                       (select code from product_type order by code limit 1), 'Bulk product ' || n, '{}'::jsonb,
                       '[]'::jsonb, 'None', 'LIVE', now(), now(), 0
                from generate_series(1, :count) n
                """).param("prefix", prefix).param("org", org).param("count", count).update();
        jdbc.sql("""
                insert into product_variant (id, product_id, atin, owner_org, seller_sku, gtin, gtin_exempt, options,
                                             price_minor, currency, quantity, position)
                select gen_random_uuid(), p.id, 'ATV' || substr(:prefix, 2, 1) || substr(p.atin, 5), p.owner_org,
                       'SKU-' || p.atin, null, true, '{}'::jsonb, 999, 'USD', 5, 0
                from product p where p.owner_org = :org
                """).param("prefix", prefix).param("org", org).update();
    }

    private List<Object> run(UUID eventId) {
        return jdbc.sql("select status, processed, pages from listing_enforcement_run where event_id = :id")
                .param("id", eventId)
                .query((rs, n) -> List.<Object>of(rs.getString("status"), rs.getInt("processed"), rs.getInt("pages")))
                .single();
    }

    private long eventCount(String type, String org) {
        return jdbc.sql("""
                select count(*) from platform_outbox o join product p on p.id::text = o.aggregate_id
                where o.type = :t and p.owner_org = :org
                """).param("t", type).param("org", org).query(Long.class).single();
    }

    private long distinctProducts(String type, String org) {
        return jdbc.sql("""
                select count(distinct o.aggregate_id) from platform_outbox o join product p on p.id::text = o.aggregate_id
                where o.type = :t and p.owner_org = :org
                """).param("t", type).param("org", org).query(Long.class).single();
    }
}
