package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import com.afrotransact.content.application.ContentEvents;
import com.afrotransact.content.application.ContentPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Draft → publish → eligibility, country pickers and launch lists. */
class MarketsIT extends ContentIntegrationTest {

    @Test
    void publishingADisabledStateBlocksDeliveryThere() {
        JsonNode before = json(get("/v1/markets/eligibility?country=US&zip=30303"));
        assertThat(before.get("region").asString()).isEqualTo("GA");
        assertThat(before.get("deliveryAllowed").asBoolean()).isTrue();
        int version = before.get("marketsVersion").asInt();

        JsonNode draft = json(patch("/v1/markets/draft/countries/US/regions/GA", "{\"marketplace\":false}"));
        JsonNode ga = region(draft, "GA");
        assertThat(ga.get("capabilities").get("marketplace").asBoolean()).isFalse();
        assertThat(ga.get("capabilities").get("services").asBoolean()).isFalse();
        assertThat(ga.get("capabilities").get("pickup").asBoolean()).isFalse();

        assertThat(json(get("/v1/markets/eligibility?country=US&zip=30303")).get("deliveryAllowed").asBoolean())
                .as("drafts do not affect eligibility until published").isTrue();
        JsonNode changes = json(get("/v1/markets/draft/changes"));
        assertThat(changes.get("pending").asBoolean()).isTrue();
        assertThat(changes.get("disabledRegions").toString()).contains("US-GA");

        HttpResponse<String> published = post("/v1/markets/publications", "{\"note\":\"Pause Georgia\"}");
        assertThat(published.statusCode()).isEqualTo(201);
        assertThat(json(published).get("version").asInt()).isEqualTo(version + 1);
        assertThat(events(ContentEvents.MARKETS_PUBLISHED, "markets")).isPositive();

        JsonNode blocked = json(get("/v1/markets/eligibility?country=US&zip=30303"));
        assertThat(blocked.get("deliveryAllowed").asBoolean()).isFalse();
        assertThat(blocked.get("onboardingAllowed").asBoolean()).isFalse();
        assertThat(blocked.get("reason").asString()).isEqualTo("region-disabled");
        assertThat(blocked.get("marketsVersion").asInt()).isEqualTo(version + 1);
        assertThat(json(get("/v1/markets/eligibility?country=US&state=TX&zip=77042")).get("deliveryAllowed").asBoolean())
                .isTrue();

        patch("/v1/markets/draft/countries/US/regions/GA",
                "{\"marketplace\":true,\"services\":true,\"pickup\":true,\"taxRegistration\":true}");
        assertThat(post("/v1/markets/publications", "{\"note\":\"Georgia back\"}").statusCode()).isEqualTo(201);
        assertThat(json(get("/v1/markets/eligibility?country=US&zip=30303")).get("deliveryAllowed").asBoolean()).isTrue();
        HttpResponse<String> nothing = post("/v1/markets/publications", "{}");
        assertThat(nothing.statusCode()).isEqualTo(409);
        assertThat(json(nothing).get("code").asString()).isEqualTo("content.nothing-to-publish");
    }

    @Test
    void enablingACapabilityWhileTheMarketplaceIsOffIsRejected() {
        HttpResponse<String> r = patch("/v1/markets/draft/countries/US/regions/AK", "{\"food\":true}");
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(json(r).get("code").asString()).isEqualTo("content.marketplace-off");
    }

    @Test
    void nonLiveCountriesAndPickers() {
        JsonNode canada = json(get("/v1/markets/eligibility?country=CA&state=ON"));
        assertThat(canada.get("onboardingAllowed").asBoolean()).isFalse();
        assertThat(canada.get("reason").asString()).isEqualTo("country-not-live");
        assertThat(canada.get("countryStatus").asString()).isEqualTo("COMING_SOON");
        assertThat(canada.get("launchNote").asString()).contains("0% commission");
        assertThat(json(get("/v1/markets/eligibility?country=GB")).get("countryStatus").asString()).isEqualTo("PLANNED");
        assertThat(json(get("/v1/markets/eligibility?country=NG")).get("launchListOpen").asBoolean()).isTrue();

        JsonNode countries = json(get("/v1/markets/countries")).get("countries");
        assertThat(countries.size()).isGreaterThan(240);
        assertThat(countries.get(0).get("code").asString()).isEqualTo("US");
        assertThat(countries.get(0).get("regionCount").asInt()).isEqualTo(51);
        assertThat(countries.get(1).get("code").asString()).isEqualTo("CA");
        assertThat(region(json(get("/v1/markets/countries/US")), "TX").get("capabilities").get("pickup").asBoolean()).isTrue();
    }

    @Test
    void launchListForCountriesThatAreNotLive() {
        String email = "seller+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        HttpResponse<String> joined = post("/v1/markets/launch-list",
                "{\"email\":\"" + email + "\",\"country\":\"CA\",\"audience\":\"SELLER\",\"shoppingUpdates\":true}");
        assertThat(joined.statusCode()).isEqualTo(202);
        assertThat(jdbc.sql("select count(*) from launch_list_entry where email = :e and country = 'CA'")
                .param("e", email).query(Long.class).single()).isEqualTo(1);
        assertThat(events(ContentEvents.LAUNCH_LIST_JOINED, "CA")).isPositive();
        HttpResponse<String> live = post("/v1/markets/launch-list", "{\"email\":\"" + email + "\",\"country\":\"US\"}");
        assertThat(live.statusCode()).isEqualTo(422);
        assertThat(json(live).get("code").asString()).isEqualTo("content.country-live");
        assertThat(post("/v1/markets/launch-list", "{\"email\":\"nope\",\"country\":\"CA\"}").statusCode()).isEqualTo(422);
    }

    @Test
    void viewersCannotEdit() {
        TestActors.as(TestActors.staff("support", ContentPermissions.MARKETS_VIEW));
        assertThat(get("/v1/markets/draft/countries/US").statusCode()).isEqualTo(200);
        assertThat(post("/v1/markets/draft/countries/US/deactivate-all", "").statusCode()).isEqualTo(403);
    }

    private static JsonNode region(JsonNode country, String code) {
        for (JsonNode r : country.get("regions")) {
            if (r.get("code").asString().equals(code)) {
                return r;
            }
        }
        throw new AssertionError("no region " + code);
    }
}
