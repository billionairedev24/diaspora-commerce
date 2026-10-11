package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.afrotransact.seller.application.OrgProvisioningService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Staff-provisioned demo organisations (make seed): approved without an application, idempotent on id. */
class OrgProvisioningIT extends SellerIntegrationTest {

    @Test
    void staffProvisionAnApprovedSellerOnceAndOtherServicesLearnItsName() {
        String id = "org_seed_" + unique("").substring(1, 9);
        Map<String, Object> body = Map.of("id", id, "kind", "SELLER", "legalName", "Mama Put Pantry LLC",
                "displayName", "Mama Put Pantry", "country", "us");
        TestActors.as(TestActors.staff("ops-seed", OrgProvisioningService.PROVISION));

        Response created = post("/v1/orgs", body);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        assertThat(created.body().path("id").asString()).isEqualTo(id);
        assertThat(created.body().path("status").asString()).isEqualTo("APPROVED");
        assertThat(created.body().path("offering").asString()).isEqualTo("PRODUCTS");
        assertThat(created.body().path("country").asString()).isEqualTo("US");
        assertThat(events("seller.org.approved", id)).isEqualTo(1);
        JsonNode event = lastEvent("seller.org.updated", id);
        assertThat(event.path("displayName").asString()).isEqualTo("Mama Put Pantry");
        assertThat(event.path("status").asString()).isEqualTo("APPROVED");

        Response again = post("/v1/orgs", Map.of("id", id, "kind", "SELLER", "legalName", "Other LLC",
                "displayName", "Other name", "country", "US"));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("displayName").asString()).isEqualTo("Mama Put Pantry");
        assertThat(events("seller.org.approved", id)).as("no second approval").isEqualTo(1);

        assertThat(get("/v1/orgs/" + id + "/public").body().path("displayName").asString())
                .isEqualTo("Mama Put Pantry");
    }

    @Test
    void providersOfferServicesAndBadInputIsRejected() {
        TestActors.as(TestActors.staff("ops-seed", OrgProvisioningService.PROVISION));
        Response provider = post("/v1/orgs", Map.of("kind", "PROVIDER", "legalName", "Ama Braids LLC",
                "displayName", "Ama Braids", "country", "US"));
        assertThat(provider.status()).isEqualTo(201);
        assertThat(provider.body().path("id").asString()).startsWith("org_");
        assertThat(provider.body().path("offering").asString()).isEqualTo("SERVICES");

        Response bad = post("/v1/orgs", Map.of("id", "Org-Bad", "kind", "PROVIDER", "offering", "PRODUCTS",
                "legalName", "x", "displayName", "Ama", "country", "USA"));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().toString()).contains("id", "offering", "legalName", "country");

        Response retail = post("/v1/orgs", Map.of("id", "org_afrotransact_retail", "kind", "SELLER",
                "legalName", "AfroTransact", "displayName", "AfroTransact Retail", "country", "US"));
        assertThat(retail.status()).isEqualTo(422);
    }

    @Test
    void onlyStaffWithThePermissionMayProvision() {
        Map<String, Object> body = Map.of("kind", "SELLER", "legalName", "Lagos Larder LLC",
                "displayName", "Lagos Larder", "country", "US");
        TestActors.as(TestActors.staff("ops-other", "applications:review"));
        assertThat(post("/v1/orgs", body).status()).isEqualTo(403);
        TestActors.as(TestActors.member("u-prov", "org_x", "seller.owner"));
        assertThat(post("/v1/orgs", body).status()).isEqualTo(403);
    }
}
