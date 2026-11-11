package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.catalog.application.SellingHoldService;
import com.afrotransact.catalog.application.SellingHoldService.Change;
import com.afrotransact.catalog.port.BakeryClaimRepository;
import com.afrotransact.catalog.port.SellingApprovals;
import com.afrotransact.catalog.port.SellingApprovals.Status;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Selling approvals in the catalog on PostgreSQL: listings paused for an expired approval or a staff pause come back when
 * it is renewed (and a listing the seller paused themselves never does), a new rule pauses only the sellers who do not
 * qualify, and every sweep is idempotent. Nothing is deleted.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1", "at.catalog.selling-gate.page-size=3"})
@ActiveProfiles("local")
@Testcontainers
class SellingHoldsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final Status SELL = new Status("SELL", null, List.of(), List.of());
    private static final Status APPLY = new Status("APPLY", "You need approval", List.of(), List.of());

    private static final AtomicInteger SEQ = new AtomicInteger();

    @MockitoBean
    SellingApprovals approvals;

    private final SellingHoldService holds;
    private final JdbcClient jdbc;
    private final BakeryClaimRepository bakeryClaims;

    @Autowired
    SellingHoldsIT(SellingHoldService holds, JdbcClient jdbc, BakeryClaimRepository bakeryClaims) {
        this.holds = holds;
        this.jdbc = jdbc;
        this.bakeryClaims = bakeryClaims;
    }

    /** Live products of an organisation in a category (by code), with a brand and optionally a barcode. */
    private List<UUID> seed(String org, String categoryCode, String brand, int count, String status) {
        String prefix = String.format("H%03d", SEQ.incrementAndGet());
        jdbc.sql("""
                insert into product (id, atin, owner_kind, owner_org, category_id, product_type, title, attributes, images,
                                     variation_theme, status, created_at, updated_at, version)
                select gen_random_uuid(), 'AT' || :prefix || lpad(n::text, 4, '0'), 'SELLER', :org,
                       (select id from category where code = :cat), (select code from product_type order by code limit 1),
                       'Held product ' || n, jsonb_build_object('brand', cast(:brand as text)), '[]'::jsonb, 'None', :status,
                       now(), now(), 0
                from generate_series(1, :count) n
                """).param("prefix", prefix).param("org", org).param("cat", categoryCode)
                .param("brand", brand).param("status", status).param("count", count).update();
        return jdbc.sql("select id from product where owner_org = :org and atin like :p order by id")
                .param("org", org).param("p", "AT" + prefix + "%").query(UUID.class).list();
    }

    private long count(String org, String status) {
        return jdbc.sql("select count(*) from product where owner_org = :org and status = :s").param("org", org)
                .param("s", status).query(Long.class).single();
    }

    private long holdsOf(String org) {
        return jdbc.sql("select count(*) from product_selling_hold where org_id = :org").param("org", org)
                .query(Long.class).single();
    }

    private long events(String type, String org) {
        return jdbc.sql("select count(*) from platform_outbox o join product p on p.id::text = o.aggregate_id "
                + "where o.type = :t and p.owner_org = :org").param("t", type).param("org", org).query(Long.class).single();
    }

    @Test
    void anExpiredApprovalPausesTheScopeListingsInPagesAndRenewalBringsThemBackNothingDeleted() {
        String org = "org_hold_a";
        List<UUID> live = seed(org, "dietary-supplements", "Optimum", 8, "LIVE");
        List<UUID> sellerPaused = seed(org, "dietary-supplements", "Optimum", 2, "PAUSED");
        seed(org, "packaged-snacks", "Tasty", 2, "LIVE");
        Change expired = new Change(org, "CATEGORY", "dietary-supplements", "EXPIRED", List.of());

        assertThat(holds.pause(expired)).isEqualTo(8);
        assertThat(holds.pause(expired)).as("a redelivery pauses nothing more").isZero();
        assertThat(count(org, "PAUSED")).isEqualTo(10);
        assertThat(count(org, "LIVE")).as("other scopes keep selling").isEqualTo(2);
        assertThat(holdsOf(org)).isEqualTo(8);
        assertThat(events("catalog.product.paused", org)).isEqualTo(8);
        assertThat(count(org, "REJECTED") + count(org, "TAKEN_DOWN")).as("nothing is taken down").isZero();

        assertThat(holds.restore(new Change(org, "CATEGORY", "dietary-supplements", "RENEWED", List.of()))).isEqualTo(8);
        assertThat(holds.restore(new Change(org, "CATEGORY", "dietary-supplements", "RENEWED", List.of()))).isZero();
        assertThat(count(org, "LIVE")).isEqualTo(10);
        assertThat(count(org, "PAUSED")).as("the seller's own pause stays").isEqualTo(2);
        assertThat(holdsOf(org)).isZero();
        assertThat(events("catalog.product.resumed", org)).isEqualTo(8);
        assertThat(live).hasSize(8);
        assertThat(sellerPaused).hasSize(2);
    }

    @Test
    void aListingHeldTwiceComesBackOnlyWhenTheLastHoldIsReleased() {
        String org = "org_hold_b";
        seed(org, "dietary-supplements", "Gucci", 2, "LIVE");
        holds.pause(new Change(org, "CATEGORY", "dietary-supplements", "PERFORMANCE", List.of()));
        holds.pause(new Change(org, "BRAND", "gucci", "EXPIRED", List.of()));
        assertThat(count(org, "PAUSED")).isEqualTo(2);
        assertThat(holdsOf(org)).as("the second scope found them already paused").isEqualTo(2);
        assertThat(holds.restore(new Change(org, "CATEGORY", "dietary-supplements", "STAFF_RESTORED", List.of()))).isEqualTo(2);
        assertThat(count(org, "LIVE")).isEqualTo(2);
    }

    @Test
    void everyScopeTypeFindsItsListings() {
        String org = "org_hold_c";
        seed(org, "dietary-supplements", "Levi's", 1, "LIVE");
        seed(org, "bakery-products", "Mama's Oven", 1, "LIVE");
        seed(org, "prepared-meals", "Mama's Oven", 1, "LIVE");
        seed(org, "packaged-snacks", "Tasty", 1, "LIVE");
        assertThat(holds.pause(new Change(org, "BRAND", "levis", "RULE", List.of()))).isEqualTo(1);
        assertThat(holds.pause(new Change(org, "BRAND_IN_CATEGORY", "mamasoven|bakery-products", "RULE", List.of())))
                .isEqualTo(1);
        assertThat(holds.pause(new Change(org, "CAPABILITY", "FOOD_SERVICE", "EXPIRED",
                List.of("bakery-products", "prepared-meals")))).as("the bakery one is already held").isEqualTo(1);
        assertThat(holds.pause(new Change(org, "CAPABILITY", "FOOD_SERVICE", "EXPIRED", List.of()))).isZero();
        assertThat(count(org, "LIVE")).isEqualTo(1);
        jdbc.sql("update product set status = 'LIVE' where owner_org = :o").param("o", org).update();
        String type = jdbc.sql("select product_type from product where owner_org = :o limit 1").param("o", org)
                .query(String.class).single();
        assertThat(holds.pause(new Change(org, "PRODUCT_TYPE", type, "RULE", List.of()))).isEqualTo(4);
    }

    @Test
    void aNewRulePausesOnlySellersWhoDoNotQualifyAndRestoresThemWhenTheyDo() {
        String approved = "org_hold_d1";
        String missing = "org_hold_d2";
        seed(approved, "dietary-supplements", "Optimum", 2, "LIVE");
        seed(missing, "dietary-supplements", "Optimum", 4, "LIVE");
        when(approvals.statusOf(any(), any())).thenReturn(SELL); // every other seller in the shared database
        when(approvals.statusOf(eq(approved), any())).thenReturn(SELL);
        when(approvals.statusOf(eq(missing), any())).thenReturn(APPLY);
        Change rule = new Change(null, "CATEGORY", "dietary-supplements", "RULE", List.of());

        assertThat(holds.ruleChanged(rule)).isEqualTo(4);
        assertThat(count(approved, "LIVE")).isEqualTo(2);
        assertThat(count(missing, "PAUSED")).isEqualTo(4);
        assertThat(holds.ruleChanged(rule)).as("re-running changes nothing").isZero();

        when(approvals.statusOf(eq(missing), any())).thenReturn(SELL);
        assertThat(holds.ruleChanged(rule)).as("the seller was approved meanwhile, or the rule was relaxed").isEqualTo(4);
        assertThat(count(missing, "LIVE")).isEqualTo(4);
        assertThat(holdsOf(missing)).isZero();
    }

    @Test
    void theOldBakeryAnswerIsListedPerOrganisationForTheFoodServiceMigration() {
        String org = "org_hold_e";
        seed(org, "bakery-products", "Mama's Oven", 2, "LIVE");
        seed("org_hold_e2", "bakery-products", "Mama's Oven", 1, "LIVE");
        jdbc.sql("update product set listing = jsonb_build_object('sellerBehaviour', jsonb_build_object('seller_is_bakery', "
                + "'yes')) where owner_org = :o").param("o", org).update();
        jdbc.sql("update product set listing = jsonb_build_object('sellerBehaviour', jsonb_build_object('seller_is_bakery', "
                + "'no')) where owner_org = 'org_hold_e2'").update();
        assertThat(bakeryClaims.organisations()).contains(org).doesNotContain("org_hold_e2");
    }
}
