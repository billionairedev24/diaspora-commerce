package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.OrgDirectoryService;
import com.afrotransact.seller.application.SellerPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S213: the paged organisation directory the indexer warms its seller names from. */
class OrgDirectoryIT extends SellerIntegrationTest {

    private void org(String id, String kind, String legal, String display, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into organisation (id, kind, legal_name, display_name, country, status, created_at, updated_at)
                values (:id, :kind, :legal, :display, 'US', :status, :now, :now)
                """).params(Map.of("id", id, "kind", kind, "legal", legal, "status", status, "now", now))
                .param("display", display).update();
    }

    @Test
    void indexerReadsEveryOrganisationPageByPage() {
        String prefix = "org_zzdir" + unique("").substring(1, 7);
        org(prefix + "1", "SELLER", "Kemi Foods LLC", "Kemi's Pantry", "APPROVED");
        org(prefix + "2", "PROVIDER", "Ama Braids LLC", null, "SUSPENDED");
        org(prefix + "3", "SELLER", "Draft Co", "  ", "DRAFT");
        TestActors.as(TestActors.service("indexer-service", OrgDirectoryService.DIRECTORY_READ));

        Response first = get("/v1/orgs/directory?page=0&size=2");
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        long total = first.body().path("total").asLong();
        assertThat(total).isGreaterThanOrEqualTo(3);
        assertThat(first.body().path("items").size()).isEqualTo(2);

        List<JsonNode> mine = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int page = 0; page * 2L < total; page++) {
            Response r = get("/v1/orgs/directory?page=" + page + "&size=2");
            r.body().path("items").forEach(item -> {
                ids.add(item.path("id").asString());
                if (item.path("id").asString().startsWith(prefix)) {
                    mine.add(item);
                }
            });
        }
        assertThat(ids).doesNotHaveDuplicates().isSorted().hasSize((int) total);
        assertThat(mine).hasSize(3);
        assertThat(mine.get(0).path("displayName").asString()).isEqualTo("Kemi's Pantry");
        assertThat(mine.get(0).path("kind").asString()).isEqualTo("SELLER");
        assertThat(mine.get(1).path("displayName").asString()).as("legal name until a store name is set")
                .isEqualTo("Ama Braids LLC");
        assertThat(mine.get(1).path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(mine.get(2).path("displayName").asString()).as("blank store name falls back").isEqualTo("Draft Co");

        Response suspended = get("/v1/orgs/directory?size=500&status=suspended");
        assertThat(suspended.status()).isEqualTo(200);
        suspended.body().path("items").forEach(i -> assertThat(i.path("status").asString()).isEqualTo("SUSPENDED"));

        assertThat(get("/v1/orgs/directory?size=501").status()).isEqualTo(422);
        assertThat(get("/v1/orgs/directory?status=NOPE").status()).isEqualTo(400);
    }

    @Test
    void directoryIsForServicesAndStaffWithTheScope() {
        TestActors.as(TestActors.service("payments-service", "orgs:read"));
        assertThat(get("/v1/orgs/directory").status()).isEqualTo(403);
        TestActors.as(TestActors.member("u-dir", "org_x", "seller.owner"));
        assertThat(get("/v1/orgs/directory").status()).isEqualTo(403);
        TestActors.as(TestActors.staff("ops-dir", OrgDirectoryService.DIRECTORY_READ, SellerPermissions.SELLERS_ENFORCE));
        assertThat(get("/v1/orgs/directory").status()).isEqualTo(200);
    }

    @Test
    void identityReadsOrganisationsAsItMirrorsThemWithTheirOwners() {
        String id = "org_zzmir" + unique("").substring(1, 7);
        org(id, "SELLER", "Malo Branding LLC", "Malo Branding", "APPROVED");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into org_member (org_id, user_id, role_key, created_at, updated_at)
                values (:org, :user, 'seller.owner', :now, :now), (:org, :staff, 'seller.catalog', :now, :now)
                """).params(Map.of("org", id, "user", "11111111-2222-3333-4444-555555555555",
                "staff", "99999999-2222-3333-4444-555555555555", "now", now)).update();
        TestActors.as(TestActors.service("identity-service", OrgDirectoryService.DIRECTORY_READ));

        Response one = get("/v1/orgs/" + id + "/mirror");
        assertThat(one.status()).as(one.body().toString()).isEqualTo(200);
        assertThat(one.body().path("orgId").asString()).isEqualTo(id);
        assertThat(one.body().path("status").asString()).isEqualTo("APPROVED");
        assertThat(one.body().path("kind").asString()).isEqualTo("SELLER");
        assertThat(one.body().path("displayName").asString()).isEqualTo("Malo Branding");
        assertThat(one.body().path("ownerUserIds").toString()).isEqualTo("[\"11111111-2222-3333-4444-555555555555\"]");
        assertThat(one.body().path("occurredAt").asString()).isNotBlank();

        Response approved = get("/v1/orgs/mirror?size=500&status=APPROVED,SUSPENDED");
        assertThat(approved.status()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        approved.body().path("items").forEach(i -> ids.add(i.path("orgId").asString()));
        assertThat(ids).contains(id).isSorted();

        assertThat(get("/v1/orgs/org_zznope/mirror").status()).isEqualTo(404);
        TestActors.as(TestActors.member("u-mir", id, "seller.owner"));
        assertThat(get("/v1/orgs/" + id + "/mirror").status()).isEqualTo(403);
        assertThat(get("/v1/orgs/mirror").status()).isEqualTo(403);
    }
}
