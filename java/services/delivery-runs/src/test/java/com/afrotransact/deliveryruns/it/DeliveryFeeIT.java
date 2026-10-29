package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.PreorderWindowService;
import com.afrotransact.deliveryruns.application.WindowCommand;
import com.afrotransact.deliveryruns.application.WindowView;
import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0005 §3 against real PostgreSQL: delivery fees are staff-managed rules by method; one resolution gives the
 * window's door fee (distance band of its location), pickup fee, carrier rate and the beyond-coverage routing; every
 * change needs a reason and is audited; a window's own fee overrides the rule. Set {@code AT_IT_POSTGRES_URL}
 * (jdbc:postgresql://…) to run without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false",
    "at.delivery-runs.geo.base-url=http://127.0.0.1:1"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class DeliveryFeeIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final String DOOR = "/v1/delivery-fees/AFROTRANSACT_DELIVERY/USD";

    record Res(int status, JsonNode body, String etag) {
    }

    private final RestClient http;
    private final JsonMapper json;
    private final MutableClock clock;
    private final FakeUpstreams upstreams;
    private final PreorderWindowService windows;
    private final LocationService locations;

    @Autowired
    DeliveryFeeIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, FakeUpstreams upstreams,
                  PreorderWindowService windows, LocationService locations) {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.json = json;
        this.clock = clock;
        this.upstreams = upstreams;
        this.windows = windows;
        this.locations = locations;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @AfterEach
    void restoreLaunchRules() {
        ItSupport.reset();
        put(DOOR, Map.of("bands", List.of(band(0, 100, 799)), "reason", "Test clean-up"), null);
        put("/v1/delivery-fees/PICKUP/USD", Map.of("feeMinor", 0, "reason", "Test clean-up"), null);
    }

    private static Map<String, Object> band(int from, int to, int fee) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("fromMiles", from);
        b.put("toMiles", to);
        b.put("feeMinor", fee);
        return b;
    }

    @Test
    void theExistingFeesAreCarriedOverAsVersionOneOfTheRules() {
        Res rules = call(HttpMethod.GET, "/v1/delivery-fees", null, null);
        assertThat(rules.status()).isEqualTo(200);
        JsonNode door = rule(rules.body(), "AFROTRANSACT_DELIVERY", "USD");
        assertThat(door.path("bands")).hasSize(1);
        assertThat(door.path("coverageMiles").asInt()).isEqualTo(100);
        assertThat(rule(rules.body(), "PICKUP", "USD").path("feeMinor").asLong()).isZero();
        JsonNode carrier = rule(rules.body(), "CARRIER", "USD");
        assertThat(carrier.path("fallbackMinor").asLong()).isEqualTo(599);
        assertThat(carrier.path("freeAboveMinor").asLong()).isEqualTo(3500);

        Res publicRules = call(HttpMethod.GET, "/v1/public/delivery-fees?currency=USD", null, null);
        assertThat(publicRules.status()).isEqualTo(200);
        assertThat(rule(publicRules.body(), "CARRIER", "USD").path("fallbackMinor").asLong()).isEqualTo(599);
        assertThat(call(HttpMethod.GET, "/v1/public/delivery-fees?currency=EUR", null, null).body().path("items"))
                .isEmpty();

        Res settings = call(HttpMethod.GET, "/v1/preorder-settings", null, null);
        assertThat(settings.body().path("payment").has("doorFee")).as("the flat fee maps are gone").isFalse();
    }

    @Test
    void changingARuleNeedsAReasonIsVersionedAndAudited() {
        Res before = call(HttpMethod.GET, "/v1/delivery-fees", null, null);
        long version = rule(before.body(), "AFROTRANSACT_DELIVERY", "USD").path("version").asLong();

        Res noReason = put(DOOR, Map.of("bands", List.of(band(0, 20, 599), band(20, 60, 899))), null);
        assertThat(noReason.status()).isIn(400, 422);
        Res blank = put(DOOR, Map.of("bands", List.of(band(0, 60, 599)), "reason", "  "), null);
        assertThat(blank.status()).isIn(400, 422);

        Res saved = put(DOOR, Map.of("bands", List.of(band(0, 20, 599), band(20, 60, 899)),
                "reason", "Texas launch rates"), "\"" + version + "\"");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.etag()).isEqualTo("\"" + (version + 1) + "\"");
        assertThat(saved.body().path("version").asLong()).isEqualTo(version + 1);
        assertThat(saved.body().path("updatedBy").asString()).isEqualTo("local-dev");
        assertThat(saved.body().path("coverageMiles").asInt()).isEqualTo(60);

        Res stale = put(DOOR, Map.of("bands", List.of(band(0, 60, 999)), "reason", "Stale"), "\"" + version + "\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().path("code").asString()).isEqualTo("delivery-runs.version-mismatch");

        Res gap = put(DOOR, Map.of("bands", List.of(band(0, 20, 599), band(25, 60, 899)), "reason", "Gap"), null);
        assertThat(gap.status()).isEqualTo(422);
        assertThat(gap.body().path("code").asString()).isEqualTo("delivery-runs.invalid-fee-band");
        Res tooHigh = put(DOOR, Map.of("bands", List.of(band(0, 60, 10_001)), "reason", "Typo"), null);
        assertThat(tooHigh.status()).isIn(400, 422);

        Res same = put(DOOR, Map.of("bands", List.of(band(0, 20, 599), band(20, 60, 899)), "reason", "Looked again"),
                null);
        assertThat(same.body().path("version").asLong()).as("no change, no new version").isEqualTo(version + 1);

        Res changes = call(HttpMethod.GET, "/v1/delivery-fees/changes?method=AFROTRANSACT_DELIVERY&currency=USD",
                null, null);
        JsonNode latest = changes.body().path("items").get(0);
        assertThat(latest.path("version").asLong()).isEqualTo(version + 1);
        assertThat(latest.path("reason").asString()).isEqualTo("Texas launch rates");
        assertThat(latest.path("actor").asString()).isEqualTo("local-dev");
        assertThat(latest.path("before").path("bands")).hasSize(1);
        assertThat(latest.path("after").path("bands")).hasSize(2);
        assertThat(changes.body().path("items")).extracting(i -> i.path("method").asString())
                .containsOnly("AFROTRANSACT_DELIVERY");

        Res first = put("/v1/delivery-fees/PICKUP/MXN", Map.of("feeMinor", 150, "reason", "Add Mexico"), null);
        assertThat(first.status()).as("a currency without a rule gets its first version").isEqualTo(200);
        assertThat(first.body().path("version").asLong()).isPositive();
    }

    @Test
    void aWindowPaysTheBandOfItsLocationAndTheOrderCanKeepTheRuleItWasChargedBy() {
        put(DOOR, Map.of("bands", List.of(band(0, 10, 599), band(10, 40, 899), band(40, 60, 1299)),
                "reason", "Three bands"), null);
        put("/v1/delivery-fees/PICKUP/USD", Map.of("feeMinor", 100, "reason", "Small pickup fee"), null);
        locations.create(Fixtures.town(Fixtures.slug("smithville"), "Smithville"));
        String id = publish("Fee Bands #1", "US/TX/Smithville", true).window().id().toString();
        Res rules = call(HttpMethod.GET, "/v1/delivery-fees", null, null);
        JsonNode door = rule(rules.body(), "AFROTRANSACT_DELIVERY", "USD");

        // Smithville is 32 road miles from the hub (the fake geo's Austin route): the 10–40 mile band.
        Res window = call(HttpMethod.GET, "/v1/preorders/" + id, null, null);
        assertThat(window.body().path("effectiveDoorFee").asLong()).isEqualTo(899);
        assertThat(window.body().path("effectivePickupFee").asLong()).isEqualTo(100);
        JsonNode ref = window.body().path("doorFeeRule");
        assertThat(ref.path("method").asString()).isEqualTo("AFROTRANSACT_DELIVERY");
        assertThat(ref.path("ruleId").asString()).isEqualTo(door.path("id").asString());
        assertThat(ref.path("ruleVersion").asLong()).isEqualTo(door.path("version").asLong());
        assertThat(ref.path("band").asString()).isEqualTo("10–40 mi");
        assertThat(ref.path("distanceMiles").asDouble()).isEqualTo(32.0);
        assertThat(ref.path("source").asString()).isEqualTo("RULE");

        // The storefront and orders-service get the same answer, rule included.
        upstreams.product("EGU", "Pantry", false);
        Res eligible = call(HttpMethod.GET, "/v1/service/preorders/eligibility?country=US&region=TX&city=Smithville"
                + "&atins=EGU", null, null);
        JsonNode egusi = eligible.body().path("items").get(0);
        assertThat(egusi.path("preorder").asBoolean()).isTrue();
        assertThat(egusi.path("doorFeeMinor").asLong()).isEqualTo(899);
        assertThat(egusi.path("doorFeeRule").path("ruleVersion").asLong()).isEqualTo(door.path("version").asLong());
        assertThat(egusi.path("doorFeeRule").path("band").asString()).isEqualTo("10–40 mi");
        assertThat(egusi.path("pickupFeeRule").path("method").asString()).isEqualTo("PICKUP");
        assertThat(eligible.body().path("fees").path("doorFeeRule").path("amountMinor").asLong()).isEqualTo(899);
        assertThat(eligible.body().path("routedTo").isNull()).isTrue();
        Res resolved = call(HttpMethod.GET, "/v1/public/preorders/resolve?country=US&region=TX&city=Smithville", null,
                null);
        assertThat(resolved.body().path("fees").path("doorFeeMinor").asLong()).isEqualTo(899);

        // Staff edit the rule: the next quote differs, but the earlier snapshot named version N.
        long before = door.path("version").asLong();
        put(DOOR, Map.of("bands", List.of(band(0, 10, 599), band(10, 40, 999), band(40, 60, 1299)),
                "reason", "Fuel"), null);
        Res after = call(HttpMethod.GET, "/v1/service/preorders/eligibility?country=US&region=TX&city=Smithville"
                + "&atins=EGU", null, null);
        assertThat(after.body().path("items").get(0).path("doorFeeMinor").asLong()).isEqualTo(999);
        assertThat(after.body().path("items").get(0).path("doorFeeRule").path("ruleVersion").asLong())
                .isEqualTo(before + 1);
    }

    @Test
    void beyondTheLastBandTheOrderRoutesToCarrierInsteadOfARun() {
        put(DOOR, Map.of("bands", List.of(band(0, 20, 599)), "reason", "Short range"), null);
        locations.create(Fixtures.town(Fixtures.slug("giddings"), "Giddings"));
        publish("Too Far #1", "US/TX/Giddings", false);
        upstreams.product("EGU", "Pantry", false);

        Res eligible = call(HttpMethod.GET, "/v1/service/preorders/eligibility?country=US&region=TX&city=Giddings"
                + "&atins=EGU", null, null);
        assertThat(eligible.body().path("on").asBoolean()).isFalse();
        assertThat(eligible.body().path("routedTo").asString()).isEqualTo("CARRIER");
        assertThat(eligible.body().path("items").get(0).path("preorder").asBoolean()).isFalse();
        Res resolved = call(HttpMethod.GET, "/v1/public/preorders/resolve?country=US&region=TX&city=Giddings", null,
                null);
        assertThat(resolved.body().path("on").asBoolean()).isFalse();

        Res carrier = call(HttpMethod.POST, "/v1/service/delivery-fees/carrier", Map.of("currency", "USD",
                "itemsMinor", 2000, "carrierQuoteMinor", 1250), null);
        assertThat(carrier.status()).isEqualTo(200);
        assertThat(carrier.body().path("applies").asBoolean()).isTrue();
        assertThat(carrier.body().path("fee").path("amountMinor").asLong()).isEqualTo(1250);
        assertThat(carrier.body().path("fee").path("source").asString()).isEqualTo("CARRIER_QUOTE");
        Res noQuote = call(HttpMethod.POST, "/v1/service/delivery-fees/carrier", Map.of("currency", "USD",
                "itemsMinor", 2000), null);
        assertThat(noQuote.body().path("fee").path("amountMinor").asLong()).isEqualTo(599);
        assertThat(noQuote.body().path("fee").path("source").asString()).isEqualTo("CARRIER_FALLBACK");
        Res free = call(HttpMethod.POST, "/v1/service/delivery-fees/carrier", Map.of("currency", "USD",
                "itemsMinor", 5000), null);
        assertThat(free.body().path("fee").path("amountMinor").asLong()).isZero();
        Res none = call(HttpMethod.POST, "/v1/service/delivery-fees/carrier", Map.of("currency", "EUR",
                "itemsMinor", 5000), null);
        assertThat(none.body().path("applies").asBoolean()).isFalse();
    }

    @Test
    void aFeeStaffSetOnTheWindowOverridesTheRuleAndPreviewShowsEveryMethod() {
        locations.create(Fixtures.town(Fixtures.slug("lockhart"), "Lockhart"));
        Instant now = clock.instant();
        Map<String, Object> body = windowBody("Override #1", "US/TX/Lockhart", now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(4)));
        body.put("doorFeeOverride", 450);
        body.put("pickupFeeOverride", 0);
        Res created = call(HttpMethod.POST, "/v1/preorders", body, null);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("effectiveDoorFee").asLong()).isEqualTo(450);
        assertThat(created.body().path("doorFeeRule").path("source").asString()).isEqualTo("WINDOW_OVERRIDE");
        String id = created.body().path("id").asString();

        Res preview = call(HttpMethod.POST, "/v1/delivery-fees/preview", Map.of("method", "AFROTRANSACT_DELIVERY",
                "currency", "USD", "windowId", id), null);
        assertThat(preview.body().path("fee").path("amountMinor").asLong()).isEqualTo(450);
        Res byMiles = call(HttpMethod.POST, "/v1/delivery-fees/preview", Map.of("method", "AFROTRANSACT_DELIVERY",
                "currency", "USD", "distanceMiles", 5), null);
        assertThat(byMiles.body().path("applies").asBoolean()).isTrue();
        Res beyond = call(HttpMethod.POST, "/v1/delivery-fees/preview", Map.of("method", "AFROTRANSACT_DELIVERY",
                "currency", "USD", "distanceMiles", 500), null);
        assertThat(beyond.body().path("applies").asBoolean()).isFalse();
        assertThat(call(HttpMethod.POST, "/v1/delivery-fees/preview", Map.of("method", "PICKUP", "currency", "USD"),
                null).body().path("applies").asBoolean()).isTrue();
        assertThat(call(HttpMethod.POST, "/v1/delivery-fees/preview", Map.of("method", "NOPE", "currency", "USD"),
                null).status()).isEqualTo(400);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static JsonNode rule(JsonNode rules, String method, String currency) {
        for (JsonNode r : rules.path("items")) {
            if (method.equals(r.path("method").asString()) && currency.equals(r.path("currency").asString())) {
                return r;
            }
        }
        throw new AssertionError("no " + method + " rule in " + currency + ": " + rules);
    }

    private WindowView publish(String name, String target, boolean pickup) {
        Instant now = clock.instant();
        Instant opens = now.minus(Duration.ofDays(1));
        Instant closes = now.plus(Duration.ofDays(4));
        LocalDate delivers = closes.plus(Duration.ofDays(2)).atZone(CHICAGO).toLocalDate();
        WindowView v = windows.create(new WindowCommand(name, List.of(target), opens, closes, delivers,
                "America/Chicago", name + " is open", "Fresh egusi delivered", "AfroTransact: pre-order open",
                Set.of("Pantry", "Spices"), true, pickup, null, null, true, 20_000L, Set.of(CommKind.ANNOUNCE), null,
                null, null, null));
        WindowView withHero = windows.setCreative(v.window().id(), CreativeSlot.HERO, upstreams.image(1920, 640),
                v.window().version());
        return windows.publish(v.window().id(), withHero.window().version());
    }

    private Map<String, Object> windowBody(String name, String target, Instant opens, Instant closes) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("targets", List.of(target));
        b.put("opensAt", opens.toString());
        b.put("closesAt", closes.toString());
        b.put("deliversOn", closes.plus(Duration.ofDays(3)).atZone(CHICAGO).toLocalDate().toString());
        b.put("timeZone", "America/Chicago");
        b.put("ribbon", "Pre-order is open");
        b.put("headline", "Fresh egusi");
        b.put("smsCopy", "AfroTransact: pre-order open. Reply STOP to opt out");
        b.put("categories", List.of("Pantry"));
        b.put("door", true);
        b.put("pickup", true);
        return b;
    }

    private Res put(String uri, Object body, String ifMatch) {
        return exchange(http.put().uri(uri), body, ifMatch);
    }

    private Res call(HttpMethod method, String uri, Object body, String ifMatch) {
        return exchange(http.method(method).uri(uri), body, ifMatch);
    }

    private Res exchange(RestClient.RequestBodySpec spec, Object body, String ifMatch) {
        if (ifMatch != null) {
            spec = spec.header("If-Match", ifMatch);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes(),
                res.getHeaders().getETag()));
    }

    private Res read(int status, byte[] bytes, String etag) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (RuntimeException e) {
            node = json.createObjectNode();
        }
        return new Res(status, node, etag);
    }
}
