package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.domain.Cadence;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S255: ship-from addresses are geocoded through geo-service and can become AfroTransact Delivery pickup locations
 * (approved organisation, pickup hours, inside or near an active DELIVERY zone). geo-service is stubbed by WireMock.
 */
class AfrotransactPickupIT extends SellerIntegrationTest {

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    private static Map<String, Object> address(String line1, String zip) {
        Map<String, Object> a = new HashMap<>();
        a.put("label", "Store " + zip);
        a.put("name", "Ama Mensah");
        a.put("line1", line1);
        a.put("city", "Houston");
        a.put("state", "TX");
        a.put("postalCode", zip);
        return a;
    }

    private static void geocodes(String zip, double lat, double lng, String precision) {
        STRIPE.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/geocode"))
                .withRequestBody(WireMock.containing("\"postalCode\":\"" + zip + "\""))
                .willReturn(WireMock.okJson("{\"lat\":" + lat + ",\"lng\":" + lng + ",\"precision\":\"" + precision
                        + "\"}")));
    }

    private static void zones(double lat, String body) {
        STRIPE.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/zones/covering"))
                .withQueryParam("lat", WireMock.equalTo(String.valueOf(lat)))
                .withQueryParam("type", WireMock.equalTo("DELIVERY"))
                .willReturn(WireMock.okJson(body)));
    }

    private static Map<String, Object> pickup(boolean enabled, List<Map<String, Object>> hours) {
        Map<String, Object> p = new HashMap<>();
        p.put("enabled", enabled);
        p.put("hours", hours);
        return p;
    }

    private static final List<Map<String, Object>> WEEKDAYS = List.of(
            Map.of("day", "MONDAY", "opens", "09:00", "closes", "17:00"),
            Map.of("day", "TUESDAY", "opens", "09:00", "closes", "12:00"),
            Map.of("day", "TUESDAY", "opens", "13:00", "closes", "17:30"));

    @Test
    void approvedSellersEnablePickupInsideTheDeliveryArea() {
        String owner = unique("pick");
        String org = approvedSeller(owner, "seller_essentials", Cadence.MONTHLY);
        String base = "/v1/orgs/" + org + "/ship-from-addresses";
        geocodes("77002", 29.7589, -95.3677, "ADDRESS");
        geocodes("77494", 29.7858, -95.8245, "ADDRESS");
        geocodes("77001", 29.7600, -95.3700, "ZIP");
        zones(29.7589, "{\"zones\":[{\"name\":\"Houston core\",\"active\":true,\"inside\":true,\"distanceMiles\":0}]}");
        zones(29.7858, "{\"zones\":[]}");

        TestActors.as(TestActors.member(unique("own"), org, "seller.owner"));
        JsonNode store = post(base, address("123 Main St", "77002")).body();
        assertThat(store.path("position").path("lat").asDouble()).isEqualTo(29.7589);
        assertThat(store.path("position").path("precision").asString()).isEqualTo("ADDRESS");
        assertThat(store.path("afrotransactPickup").path("enabled").asBoolean()).isFalse();
        String storeId = store.path("id").asString();

        assertThat(put(base + "/" + storeId + "/afrotransact-pickup", pickup(true, List.of())).text("/code"))
                .isEqualTo("seller.invalid-pickup-hours");
        Response bad = put(base + "/" + storeId + "/afrotransact-pickup", pickup(true, List.of(
                Map.of("day", "MONDAY", "opens", "18:00", "closes", "09:00"))));
        assertThat(bad.status()).isEqualTo(422);

        Response on = put(base + "/" + storeId + "/afrotransact-pickup", pickup(true, WEEKDAYS));
        assertThat(on.status()).as(on.body().toString()).isEqualTo(200);
        assertThat(on.body().path("afrotransactPickup").path("enabled").asBoolean()).isTrue();
        assertThat(on.text("/afrotransactPickup/zoneName")).isEqualTo("Houston core");
        assertThat(on.body().path("afrotransactPickup").path("hours")).hasSize(3);
        assertThat(events("seller.pickup_location.updated", org)).isEqualTo(1);
        assertThat(lastEvent("seller.pickup_location.updated", org).path("enabled").asBoolean()).isTrue();

        // Outside every delivery zone (and further than the radius): refused.
        String katy = post(base, address("1 Katy Fwy", "77494")).text("/id");
        Response far = put(base + "/" + katy + "/afrotransact-pickup", pickup(true, WEEKDAYS));
        assertThat(far.status()).isEqualTo(422);
        assertThat(far.text("/code")).isEqualTo("seller.pickup-outside-delivery-area");
        // Only a ZIP centroid: a driver can't be sent there.
        String vague = post(base, address("PO Box 12", "77001")).text("/id");
        assertThat(put(base + "/" + vague + "/afrotransact-pickup", pickup(true, WEEKDAYS)).text("/code"))
                .isEqualTo("seller.ship-from-not-geocoded");

        // Services read one location by id; other principals need membership or staff permission.
        TestActors.as(TestActors.service("orders-service", "sellers:ship-from.read"));
        Response loc = get("/v1/ship-from-locations/" + storeId);
        assertThat(loc.status()).isEqualTo(200);
        assertThat(loc.body().path("afrotransactPickup").path("enabled").asBoolean()).isTrue();
        assertThat(loc.text("/orgId")).isEqualTo(org);
        TestActors.as(TestActors.service("search", "catalog:read"));
        assertThat(get("/v1/ship-from-locations/" + storeId).status()).isEqualTo(403);

        // Moving the store keeps pickup only inside the area; disabling publishes again.
        TestActors.as(TestActors.member(unique("ful"), org, "seller.fulfilment"));
        Map<String, Object> moved = address("1 Katy Fwy", "77494");
        assertThat(put(base + "/" + storeId, moved).text("/code")).isEqualTo("seller.pickup-outside-delivery-area");
        Response off = put(base + "/" + storeId + "/afrotransact-pickup", pickup(false, List.of()));
        assertThat(off.body().path("afrotransactPickup").path("enabled").asBoolean()).isFalse();
        assertThat(off.body().path("afrotransactPickup").path("hours")).as("hours kept for next time").hasSize(3);
        assertThat(events("seller.pickup_location.updated", org)).isEqualTo(2);
    }

    @Test
    void pickupNeedsAnApprovedOrganisation() {
        String owner = unique("pend");
        String org = approvedSeller(owner, "seller_essentials", Cadence.MONTHLY);
        geocodes("77002", 29.7589, -95.3677, "ADDRESS");
        TestActors.as(TestActors.member(unique("own"), org, "seller.owner"));
        String id = post("/v1/orgs/" + org + "/ship-from-addresses", address("123 Main St", "77002")).text("/id");
        jdbc.sql("update organisation set status = 'SUSPENDED' where id = :id").param("id", org).update();
        Response r = put("/v1/orgs/" + org + "/ship-from-addresses/" + id + "/afrotransact-pickup",
                pickup(true, WEEKDAYS));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("/code")).isEqualTo("seller.org-not-approved");
    }

    @Test
    void addressesAreSavedWithoutPositionWhenGeoIsDown() {
        String owner = unique("down");
        String org = approvedSeller(owner, "seller_essentials", Cadence.MONTHLY);
        STRIPE.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/geocode"))
                .withRequestBody(WireMock.containing("\"postalCode\":\"77099\""))
                .willReturn(WireMock.serverError()));
        TestActors.as(TestActors.member(unique("own"), org, "seller.owner"));
        Response saved = post("/v1/orgs/" + org + "/ship-from-addresses", address("5 Bellaire Blvd", "77099"));
        assertThat(saved.status()).isEqualTo(201);
        assertThat(saved.body().path("position").isNull()).isTrue();
        Response on = put("/v1/orgs/" + org + "/ship-from-addresses/" + saved.text("/id") + "/afrotransact-pickup",
                pickup(true, WEEKDAYS));
        assertThat(on.status()).isEqualTo(503);
        assertThat(on.text("/code")).isEqualTo("seller.geo-unavailable");
    }
}
