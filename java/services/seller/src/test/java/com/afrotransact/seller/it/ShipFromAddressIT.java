package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import com.afrotransact.seller.domain.Cadence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** S231: ship-from addresses stored per seller organisation (replaces Seller Center's browser storage). */
class ShipFromAddressIT extends SellerIntegrationTest {

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    private static Map<String, Object> address(String label, String zip) {
        Map<String, Object> a = new HashMap<>();
        a.put("label", label);
        a.put("name", "Ade Okafor");
        a.put("phone", "+1 713 555 0100");
        a.put("line1", "  4521   Main St ");
        a.put("city", "Houston");
        a.put("state", "tx");
        a.put("postalCode", zip);
        return a;
    }

    @Test
    void membersManageShipFromAddressesOfTheirOwnOrganisation() {
        String owner = unique("ship");
        String org = approvedSeller(owner, "seller_essentials", Cadence.MONTHLY);
        String base = "/v1/orgs/" + org + "/ship-from-addresses";

        TestActors.as(TestActors.member(unique("ful"), org, "seller.fulfilment"));
        Response first = post(base, address("Houston", "77002"));
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.text("/line1")).isEqualTo("4521 Main St");
        assertThat(first.text("/state")).isEqualTo("TX");
        assertThat(first.text("/country")).isEqualTo("US");
        assertThat(first.body().path("isDefault").asBoolean()).as("first address is the default").isTrue();

        Response second = post(base, address("Katy", "77494"));
        assertThat(second.body().path("isDefault").asBoolean()).isFalse();
        Response bad = post(base, address("Bad", "7700"));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().toString()).contains("postalCode");

        String secondId = second.text("/id");
        assertThat(post(base + "/" + secondId + "/default", Map.of()).body().get(0).path("id").asString())
                .isEqualTo(secondId);

        Map<String, Object> edit = address("Katy DC", "77494");
        edit.put("version", second.body().path("version").asLong());
        assertThat(put(base + "/" + secondId, edit).status()).as("stale version").isEqualTo(409);
        long current = get(base).body().get(0).path("version").asLong();
        edit.put("version", current);
        Response edited = put(base + "/" + secondId, edit);
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.text("/label")).isEqualTo("Katy DC");
        assertThat(edited.body().path("isDefault").asBoolean()).isTrue();

        Response remaining = delete(base + "/" + secondId);
        assertThat(remaining.status()).isEqualTo(200);
        assertThat(remaining.body().size()).isEqualTo(1);
        assertThat(remaining.body().get(0).path("isDefault").asBoolean()).as("default moved").isTrue();

        TestActors.as(TestActors.member(unique("cat"), org, "seller.catalog"));
        assertThat(get(base).status()).isEqualTo(200);
        assertThat(post(base, address("Nope", "77002")).status()).as("catalog members only read").isEqualTo(403);

        TestActors.as(TestActors.member(unique("other"), "org_someoneelse0000000", "seller.owner"));
        assertThat(get(base).status()).as("other organisations").isEqualTo(403);
        assertThat(delete(base + "/" + first.text("/id")).status()).isEqualTo(403);
    }
}
