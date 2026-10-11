package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.afrotransact.content.application.ContentPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Markets & locations cities: state status, inline add / switch / remove with If-Match, eligibility by city. */
class MarketCitiesIT extends ContentIntegrationTest {

    private static final String US = "/v1/markets/draft/countries/US";

    @Test
    void addingCitiesAppendsToTheExistingListAndNewOnesArePlanned() {
        JsonNode tx = region(json(get(US)), "TX");
        long v = tx.get("version").asLong();
        int before = tx.get("cityCount").asInt();

        HttpResponse<String> first = post(US + "/regions/TX/cities",
                "{\"cities\":[{\"name\":\"Katy\"},{\"name\":\"Sugar Land\",\"status\":\"LIVE\"}]}", ifMatch(v));
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode after = region(json(first), "TX");
        assertThat(after.get("cityCount").asInt()).isEqualTo(before + 2);
        assertThat(city(after, "Katy").get("status").asString()).isEqualTo("PLANNED");
        assertThat(city(after, "Sugar Land").get("status").asString()).isEqualTo("LIVE");
        long v2 = after.get("version").asLong();
        assertThat(v2).isGreaterThan(v);

        JsonNode appended = region(json(post(US + "/regions/TX/cities",
                "{\"cities\":[{\"name\":\"katy\",\"status\":\"OFF\"},{\"name\":\"Plano\"}]}", ifMatch(v2))), "TX");
        assertThat(appended.get("cityCount").asInt()).as("appends; existing Katy is kept, not replaced")
                .isEqualTo(before + 3);
        assertThat(city(appended, "Katy").get("status").asString()).isEqualTo("PLANNED");
        assertThat(city(appended, "Plano")).isNotNull();
    }

    @Test
    void staleVersionsAreRefusedWith412() {
        JsonNode tx = region(json(get(US)), "TX");
        long v = tx.get("version").asLong();
        assertThat(post(US + "/regions/TX/cities", "{\"cities\":[{\"name\":\"Round Rock\"}]}", ifMatch(v)).statusCode())
                .isEqualTo(200);
        HttpResponse<String> stale = post(US + "/regions/TX/cities", "{\"cities\":[{\"name\":\"Frisco\"}]}", ifMatch(v));
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(json(stale).get("code").asString()).isEqualTo("content.version-mismatch");
        assertThat(json(stale).get("currentVersion").asLong()).isGreaterThan(v);

        long cv = json(get(US)).get("version").asLong();
        HttpResponse<String> country = send("PATCH", US, "{\"status\":\"LIVE\"}", ifMatch(cv + 5));
        assertThat(country.statusCode()).isEqualTo(412);
    }

    @Test
    void cityStatusSwitchesInPlaceAndLiveNeedsALiveState() {
        long v = region(json(get(US)), "TX").get("version").asLong();
        JsonNode tx = region(json(post(US + "/regions/TX/cities",
                "{\"cities\":[{\"name\":\"Midland\"},{\"name\":\"Odessa\"}]}", ifMatch(v))), "TX");
        JsonNode switched = region(json(send("PATCH", US + "/regions/TX/cities",
                "{\"names\":[\"Midland\",\"odessa\"],\"status\":\"LIVE\"}", ifMatch(tx.get("version").asLong()))), "TX");
        assertThat(city(switched, "Midland").get("status").asString()).isEqualTo("LIVE");
        assertThat(city(switched, "Odessa").get("status").asString()).isEqualTo("LIVE");

        HttpResponse<String> unknown = send("PATCH", US + "/regions/TX/cities", "{\"names\":[\"Atlantis\"],\"status\":\"OFF\"}");
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(json(unknown).get("code").asString()).isEqualTo("content.unknown-city");

        // Alaska is OFF: it can be added as PLANNED but its cities can't go LIVE.
        post(US + "/regions", "{\"code\":\"AK\",\"name\":\"Alaska\"}");
        post(US + "/regions/AK/cities", "{\"cities\":[{\"name\":\"Juneau\"}]}");
        HttpResponse<String> notLive = send("PATCH", US + "/regions/AK/cities", "{\"names\":[\"Juneau\"],\"status\":\"LIVE\"}");
        assertThat(notLive.statusCode()).isEqualTo(422);
        assertThat(json(notLive).get("code").asString()).isEqualTo("content.region-not-live");
    }

    @Test
    void addingAStateMakesItPlannedAndANewOneIsInserted() {
        HttpResponse<String> added = post(US + "/regions", "{\"code\":\"OH\",\"name\":\"Ohio\"}");
        assertThat(added.statusCode()).isEqualTo(201);
        JsonNode oh = region(json(added), "OH");
        assertThat(oh.get("status").asString()).isEqualTo("PLANNED");
        assertThat(oh.get("everyCity").asBoolean()).isFalse();
        assertThat(oh.get("capabilities").get("marketplace").asBoolean()).isFalse();
        HttpResponse<String> again = post(US + "/regions", "{\"code\":\"OH\",\"name\":\"Ohio\"}");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json(again).get("code").asString()).isEqualTo("content.region-in-use");

        JsonNode ng = json(post("/v1/markets/draft/countries/NG/regions", "{\"code\":\"LA\",\"name\":\"Lagos\"}"));
        assertThat(region(ng, "LA").get("status").asString()).isEqualTo("PLANNED");
        assertThat(ng.get("plannedRegions").asInt()).isEqualTo(1);

        long v = oh.get("version").asLong();
        JsonNode live = region(json(send("PATCH", US + "/regions/OH", "{\"status\":\"LIVE\",\"everyCity\":true}", ifMatch(v))), "OH");
        assertThat(live.get("status").asString()).isEqualTo("LIVE");
        assertThat(live.get("everyCity").asBoolean()).isTrue();
        JsonNode off = region(json(send("PATCH", US + "/regions/OH", "{\"status\":\"OFF\"}")), "OH");
        assertThat(off.get("status").asString()).isEqualTo("OFF");
    }

    @Test
    void removingCitiesAndViewersCannotEdit() {
        post(US + "/regions/TX/cities", "{\"cities\":[{\"name\":\"Abilene\"},{\"name\":\"Tyler\"}]}");
        HttpResponse<String> removed = send("DELETE", US + "/regions/TX/cities?name=Abilene&name=tyler", null);
        assertThat(removed.statusCode()).isEqualTo(200);
        JsonNode tx = region(json(removed), "TX");
        assertThat(tx.get("cities").toString()).doesNotContain("Abilene").doesNotContain("Tyler");

        TestActors.as(TestActors.staff("support", ContentPermissions.MARKETS_VIEW));
        assertThat(post(US + "/regions/TX/cities", "{\"cities\":[{\"name\":\"Waco\"}]}").statusCode()).isEqualTo(403);
    }

    @Test
    void publishedCitiesDecideEligibilityWhenACityIsGiven() {
        post(US + "/regions/GA/cities", "{\"cities\":[{\"name\":\"Macon\",\"status\":\"OFF\"},{\"name\":\"Athens\",\"status\":\"LIVE\"}]}");
        // Florida lists cities only: the rest of Florida is not served by city.
        send("PATCH", US + "/regions/FL", "{\"everyCity\":false}");
        post(US + "/regions/FL/cities", "{\"cities\":[{\"name\":\"Miami\",\"status\":\"LIVE\"}]}");
        assertThat(json(get("/v1/markets/draft/changes")).get("changedCountries").toString()).contains("US");
        assertThat(post("/v1/markets/publications", "{\"note\":\"cities\"}").statusCode()).isEqualTo(201);

        JsonNode macon = json(get("/v1/markets/eligibility?country=US&zip=31201&city=Macon"));
        assertThat(macon.get("deliveryAllowed").asBoolean()).isFalse();
        assertThat(macon.get("reason").asString()).isEqualTo("city-not-live");
        assertThat(json(get("/v1/markets/eligibility?country=US&zip=30601&city=Athens")).get("deliveryAllowed").asBoolean()).isTrue();
        assertThat(json(get("/v1/markets/eligibility?country=US&zip=30303&city=Atlanta")).get("deliveryAllowed").asBoolean())
                .as("unlisted city in a state that covers every city").isTrue();
        assertThat(json(get("/v1/markets/eligibility?country=US&zip=31201")).get("deliveryAllowed").asBoolean())
                .as("no city: the state decides").isTrue();
        JsonNode tampa = json(get("/v1/markets/eligibility?country=US&zip=33601&city=Tampa"));
        assertThat(tampa.get("reason").asString()).isEqualTo("city-not-covered");
        assertThat(json(get("/v1/markets/eligibility?country=US&zip=33101&city=miami")).get("deliveryAllowed").asBoolean()).isTrue();
        assertThat(json(get("/v1/markets/eligibility?country=US&state=TX&zip=77042&city=Houston")).get("deliveryAllowed").asBoolean())
                .isTrue();

        JsonNode published = json(get("/v1/markets/countries/US"));
        assertThat(city(region(published, "FL"), "Miami").get("status").asString()).isEqualTo("LIVE");
        assertThat(region(published, "FL").get("everyCity").asBoolean()).isFalse();

        // put Georgia and Florida back so other tests see the baseline behaviour
        send("DELETE", US + "/regions/GA/cities?name=Macon&name=Athens", null);
        send("PATCH", US + "/regions/FL", "{\"everyCity\":true}");
        send("DELETE", US + "/regions/FL/cities?name=Miami", null);
        post("/v1/markets/publications", "{\"note\":\"back\"}");
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) {
        return switch (method) {
            case "PATCH" -> headers.length == 0 ? patch(path, body) : patchWith(path, body, headers);
            case "DELETE" -> delete(path, headers);
            default -> post(path, body, headers);
        };
    }

    private static String[] ifMatch(long version) {
        return new String[] {"If-Match", "\"" + version + "\""};
    }

    private static JsonNode region(JsonNode country, String code) {
        for (JsonNode r : country.get("regions")) {
            if (r.get("code").asString().equals(code)) {
                return r;
            }
        }
        throw new AssertionError("no region " + code);
    }

    private static JsonNode city(JsonNode region, String name) {
        for (JsonNode c : region.get("cities")) {
            if (c.get("name").asString().equalsIgnoreCase(name)) {
                return c;
            }
        }
        throw new AssertionError("no city " + name);
    }
}
