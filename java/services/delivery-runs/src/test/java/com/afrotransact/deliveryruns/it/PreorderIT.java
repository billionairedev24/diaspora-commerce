package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.OrderPlaced;
import com.afrotransact.deliveryruns.application.PreorderProjectionService;
import com.afrotransact.deliveryruns.application.PreorderSchedulerService;
import com.afrotransact.deliveryruns.application.PreorderSettingsService;
import com.afrotransact.deliveryruns.application.PreorderWindowService;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.application.SubscriptionService;
import com.afrotransact.deliveryruns.application.WindowCommand;
import com.afrotransact.deliveryruns.application.WindowView;
import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pre-orders end to end against real PostgreSQL: the staff API (windows, If-Match, creatives verified with media,
 * publish checks, settings with payment setup, coverage areas), the public resolve / open and service eligibility
 * endpoints, the orders projection, summary, CSV exports and late adds, and the scheduler (comms due, close and
 * hand-over to a run, delivered).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false"})
@ActiveProfiles("local")
@Import(ItSupport.class)
@Testcontainers
class PreorderIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    /** Status, parsed body, content type, raw text and ETag of a call. */
    record Res(int status, JsonNode body, String contentType, String text, String etag) {
    }

    private final RestClient http;
    private final String base;
    private final SubscriptionService subscriptions;
    private final JsonMapper json;
    private final MutableClock clock;
    private final FakeUpstreams upstreams;
    private final PreorderWindowService windows;
    private final PreorderSettingsService settings;
    private final PreorderProjectionService projection;
    private final PreorderSchedulerService scheduler;
    private final PreorderWindowRepository windowRepository;
    private final LocationService locations;
    private final RunService runs;
    private final JdbcClient jdbc;

    @Autowired
    PreorderIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, FakeUpstreams upstreams,
               PreorderWindowService windows, PreorderSettingsService settings, PreorderProjectionService projection,
               PreorderSchedulerService scheduler, PreorderWindowRepository windowRepository,
               LocationService locations, RunService runs, JdbcClient jdbc, SubscriptionService subscriptions) {
        this.base = "http://localhost:" + port;
        this.http = RestClient.builder().baseUrl(base).build();
        this.subscriptions = subscriptions;
        this.json = json;
        this.clock = clock;
        this.upstreams = upstreams;
        this.windows = windows;
        this.settings = settings;
        this.projection = projection;
        this.scheduler = scheduler;
        this.windowRepository = windowRepository;
        this.locations = locations;
        this.runs = runs;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @AfterEach
    void resetActor() {
        ItSupport.reset();
    }

    @Test
    void staffOpenAndPublishAPreorderThatShoppersResolve() {
        jdbc.sql("delete from preorder_area where city = 'Lubbock'").update();
        assertThat(call(HttpMethod.POST, "/v1/preorder-areas", Map.of("key", "US/TX/Lubbock"), null).status())
                .isEqualTo(201);
        Res dup = call(HttpMethod.POST, "/v1/preorder-areas", Map.of("key", "us/tx/LUBBOCK"), null);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().path("code").asString()).isEqualTo("delivery-runs.area-exists");

        Instant now = clock.instant();
        Res created = call(HttpMethod.POST, "/v1/preorders", windowBody("Lubbock Pre-order #7", "US/TX/Lubbock",
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(5))), null);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.etag()).isEqualTo("\"0\"");
        String id = created.body().path("id").asString();
        assertThat(created.body().path("code").asString()).startsWith("LUB-");
        assertThat(created.body().path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.body().path("checks")).extracting(c -> c.path("code").asString())
                .containsExactly("delivery-runs.preorder-hero-required");
        assertThat(created.body().path("effectiveDoorFee").asLong()).isEqualTo(799);
        assertThat(created.body().path("pickupPoint").isNull()).isTrue();

        Res blocked = call(HttpMethod.POST, "/v1/preorders/" + id + "/publish", Map.of(), null);
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().path("code").asString()).isEqualTo("delivery-runs.preorder-checks-failed");
        assertThat(blocked.body().path("violations").get(0).path("message").asString())
                .isEqualTo("Storefront hero uploaded (required)");
        assertThat(blocked.body().path("errors").path("hero").asString())
                .isEqualTo("Storefront hero uploaded (required)");

        Res pending = call(HttpMethod.PUT, "/v1/preorders/" + id + "/creatives/HERO",
                Map.of("assetId", upstreams.pendingImage()), null);
        assertThat(pending.status()).isEqualTo(422);
        assertThat(pending.body().path("code").asString()).isEqualTo("delivery-runs.asset-not-ready");
        Res hero = call(HttpMethod.PUT, "/v1/preorders/" + id + "/creatives/HERO",
                Map.of("assetId", upstreams.image(1600, 640)), "\"0\"");
        assertThat(hero.status()).isEqualTo(200);
        JsonNode heroSlot = hero.body().path("creatives").get(0);
        assertThat(heroSlot.path("slot").asString()).isEqualTo("HERO");
        assertThat(heroSlot.path("ratioOff").asBoolean()).isTrue();
        assertThat(heroSlot.path("width").asInt()).isEqualTo(1600);
        assertThat(hero.etag()).isEqualTo("\"1\"");

        Res stale = call(HttpMethod.POST, "/v1/preorders/" + id + "/publish", Map.of(), "\"0\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().path("code").asString()).isEqualTo("delivery-runs.version-mismatch");
        Res published = call(HttpMethod.POST, "/v1/preorders/" + id + "/publish", Map.of(), hero.etag());
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.body().path("status").asString()).isEqualTo("OPEN");
        assertThat(published.body().path("publishable").asBoolean()).isTrue();
        assertThat(outboxTypes(id)).contains("preorder.window.published");

        Res resolved = call(HttpMethod.GET, "/v1/public/preorders/resolve?country=US&region=TX&city=lubbock", null,
                null);
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().path("on").asBoolean()).isTrue();
        assertThat(resolved.body().path("stage").asString()).isEqualTo("OPEN");
        assertThat(resolved.body().path("window").path("id").asString()).isEqualTo(id);
        assertThat(resolved.body().path("fees").path("doorFeeMinor").asLong()).isEqualTo(799);
        assertThat(resolved.body().path("fees").path("pickupFeeMinor").asLong()).isZero();
        assertThat(resolved.body().path("count").asInt()).isZero();
        assertThat(resolved.body().path("placements").path("ribbon").asBoolean()).isTrue();
        assertThat(resolved.body().path("paymentMethods")).hasSize(3);
        assertThat(resolved.body().toString()).doesNotContain("customer");

        Res open = call(HttpMethod.GET, "/v1/public/preorders/open", null, null);
        assertThat(open.body()).extracting(n -> n.path("id").asString()).contains(id);

        upstreams.product("EGU", "Pantry", false);
        upstreams.product("SHEA", "Pantry", true);
        upstreams.product("KENTE", "Fabric", false);
        Res eligible = call(HttpMethod.GET, "/v1/service/preorders/eligibility?country=US&region=TX&city=Lubbock"
                + "&atins=EGU,SHEA,KENTE,NOPE", null, null);
        assertThat(eligible.status()).isEqualTo(200);
        assertThat(eligible.body().path("items")).extracting(n -> n.path("atin").asString() + "="
                + n.path("preorder").asBoolean()).containsExactly("EGU=true", "SHEA=false", "KENTE=false",
                "NOPE=false");
        JsonNode egusi = eligible.body().path("items").get(0);
        assertThat(egusi.path("stage").asString()).isEqualTo("OPEN");
        assertThat(egusi.path("windowCode").asString()).startsWith("LUB-");
        assertThat(egusi.path("windowName").asString()).isEqualTo("Lubbock Pre-order #7");
        assertThat(egusi.path("closesAt").asString()).isNotBlank();
        assertThat(egusi.path("doorFeeMinor").asLong()).isEqualTo(799);
        assertThat(egusi.path("pickupFeeMinor").asLong()).isZero();
        assertThat(egusi.path("currency").asString()).isEqualTo("USD");
        assertThat(egusi.path("allowedMethods")).hasSize(3);
        assertThat(egusi.path("pickupWindow").asString()).isEqualTo("10am–2pm");
        assertThat(eligible.body().path("fees").path("doorFeeMinor").asLong()).isEqualTo(799);
        assertThat(eligible.body().path("fees").path("currency").asString()).isEqualTo("USD");

        Res list = call(HttpMethod.GET, "/v1/preorders?status=open&q=lubbock", null, null);
        assertThat(list.body().path("items")).extracting(n -> n.path("id").asString()).contains(id);
        assertThat(list.body().path("counts").path("open").asInt()).isPositive();
        assertThat(list.body().path("enabled").asBoolean()).isTrue();
        assertThat(list.body().path("items").get(0).path("orderCount").isInt()).isTrue();

        Res off = call(HttpMethod.PATCH, "/v1/preorder-areas", Map.of("keys", List.of("US/TX"), "on", false), null);
        assertThat(off.status()).isEqualTo(200);
        assertThat(off.body().path("level").asString()).isEqualTo("REGION");
        assertThat(off.body().path("rows").get(0).path("on").asInt()).isZero();
        assertThat(call(HttpMethod.GET, "/v1/public/preorders/resolve?country=US&region=TX&city=Lubbock", null, null)
                .body().path("on").asBoolean()).isFalse();
        assertThat(call(HttpMethod.GET, "/v1/preorders/" + id, null, null).body().path("statusLabel").asString())
                .isEqualTo("Area off");

        Res countries = call(HttpMethod.GET, "/v1/preorder-areas", null, null);
        assertThat(countries.body().path("level").asString()).isEqualTo("COUNTRY");
        assertThat(countries.body().path("rows")).extracting(n -> n.path("key").asString() + "="
                + n.path("name").asString()).contains("US=United States");
        Res cities = call(HttpMethod.GET, "/v1/preorder-areas?country=US&region=TX&q=lub", null, null);
        assertThat(cities.body().path("level").asString()).isEqualTo("AREA");
        assertThat(cities.body().path("regionName").asString()).isEqualTo("Texas");
        assertThat(cities.body().path("regionLabel").asString()).isEqualTo("State");
        assertThat(cities.body().path("rows").get(0).path("key").asString()).isEqualTo("US/TX/Lubbock");
        assertThat(cities.body().path("rows").get(0).path("windows").asInt()).isEqualTo(1);
        assertThat(cities.body().path("rows").get(0).path("on").asInt()).isZero();
        assertThat(cities.body().path("rows").get(0).path("leaf").asBoolean()).isTrue();

        Res removed = callRaw(HttpMethod.DELETE, "/v1/preorder-areas/US%2FTX%2FLubbock");
        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.body().path("level").asString()).isEqualTo("AREA");
        assertThat(call(HttpMethod.DELETE, "/v1/preorder-areas?key=US/TX/Lubbock", null, null).status())
                .isEqualTo(404);

        Res places = call(HttpMethod.GET, "/v1/places/search?q=lubb", null, null);
        assertThat(places.body().path("items").get(0).path("key").asString()).isEqualTo("US/TX/Lubbock");
        assertThat(places.body().path("items").get(0).path("type").asString()).isEqualTo("CITY");
        Res regions = call(HttpMethod.GET, "/v1/places/search?country=CA", null, null);
        assertThat(regions.body().path("regionLabel").asString()).isEqualTo("Province");
        assertThat(regions.body().path("items")).extracting(n -> n.path("code").asString()).contains("ON");
    }

    @Test
    void settingsCarryThePaymentSetupWithValidationAndIfMatch() {
        Res current = call(HttpMethod.GET, "/v1/preorder-settings", null, null);
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().path("payment").path("doorFee").path("USD").asLong()).isEqualTo(799);
        assertThat(current.body().path("payment").path("lateAdd").path("linkExpiryHours").asInt()).isEqualTo(24);
        assertThat(current.body().path("payment").path("pickupWindow").asString()).isEqualTo("10am–2pm");
        assertThat(current.body().path("placements").path("family").asBoolean()).isTrue();
        try {
            Map<String, Object> payment = new LinkedHashMap<>();
            payment.put("doorFee", Map.of("USD", 899, "CAD", 1099));
            payment.put("allowedMethods", List.of("CARD", "WALLET"));
            payment.put("lateAdd", Map.of("savedCard", true, "paymentLink", false, "linkExpiryHours", 12));
            Res saved = call(HttpMethod.PUT, "/v1/preorder-settings", Map.of("fallback", "WAITLIST", "placements",
                    Map.of("family", false), "payment", payment), current.etag());
            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.body().path("fallback").asString()).isEqualTo("WAITLIST");
            assertThat(saved.body().path("placements").path("family").asBoolean()).isFalse();
            assertThat(saved.body().path("placements").path("hero").asBoolean()).isTrue();
            assertThat(saved.body().path("payment").path("doorFee").path("CAD").asLong()).isEqualTo(1099);
            assertThat(saved.body().path("payment").path("allowedMethods")).hasSize(2);
            assertThat(saved.body().path("payment").path("lateAdd").path("paymentLink").asBoolean()).isFalse();

            Res stale = call(HttpMethod.PUT, "/v1/preorder-settings", Map.of("enabled", true), current.etag());
            assertThat(stale.status()).isEqualTo(412);
            Res tooHigh = call(HttpMethod.PUT, "/v1/preorder-settings",
                    Map.of("payment", Map.of("doorFee", Map.of("USD", 10_001))), null);
            assertThat(tooHigh.status()).isEqualTo(422);
            assertThat(tooHigh.body().path("code").asString()).isEqualTo("delivery-runs.invalid-fee");
            Res noMethod = call(HttpMethod.PUT, "/v1/preorder-settings",
                    Map.of("payment", Map.of("allowedMethods", List.of())), null);
            assertThat(noMethod.status()).isEqualTo(422);
            assertThat(noMethod.body().path("code").asString()).isEqualTo("delivery-runs.invalid-payment-settings");
            assertThat(outboxTypes("preorder-settings")).contains("preorder.settings.changed");
        } finally {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("doorFee", Map.of("USD", 799, "CAD", 999));
            defaults.put("allowedMethods", List.of("CARD", "WALLET", "ACH"));
            defaults.put("lateAdd", Map.of("savedCard", true, "paymentLink", true, "linkExpiryHours", 24));
            assertThat(call(HttpMethod.PUT, "/v1/preorder-settings", Map.of("enabled", true, "fallback", "SHIP",
                    "placements", Map.of("family", true), "payment", defaults), null).status()).isEqualTo(200);
        }
    }

    @Test
    void ordersAreProjectedSummarisedExportedAndCustomerServiceAddsLateOrders() {
        
        Instant now = clock.instant();
        PreorderWindow w = publish("Abilene Pre-order #4", "US/TX/Abilene", now.minus(Duration.ofDays(2)),
                now.plus(Duration.ofDays(3)));

        UUID event = UUID.randomUUID();
        String orderA = UUID.randomUUID().toString();
        OrderPlaced a = placed(orderA, w.id(), "cust-a", "hero", PreorderFulfilment.DOOR, 2799, 799, now);
        projection.onOrderPlaced(event, a);
        projection.onOrderPlaced(event, a);
        String orderB = UUID.randomUUID().toString();
        projection.onOrderPlaced(UUID.randomUUID(), placed(orderB, w.id(), "cust-b", "ig", PreorderFulfilment.PICKUP,
                2000, 0, now.plusSeconds(60)));
        String orderC = UUID.randomUUID().toString();
        projection.onOrderPlaced(UUID.randomUUID(), placed(orderC, w.id(), "cust-c", null, PreorderFulfilment.DOOR,
                1799, 799, now.plusSeconds(120)));
        projection.onShipmentCancelled(UUID.randomUUID(), orderC, "shp-" + orderC, now.plusSeconds(180));

        Res paid = call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/orders?status=PAID&size=12", null, null);
        assertThat(paid.body().path("total").asInt()).isEqualTo(2);
        assertThat(paid.body().path("items")).extracting(n -> n.path("number").asString())
                .containsExactly(w.code() + "-1002", w.code() + "-1001");
        Res byPhone = call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/orders?q=0100&channel=SITE_HERO", null,
                null);
        assertThat(byPhone.body().path("items")).extracting(n -> n.path("orderId").asString()).containsExactly(orderA);
        assertThat(byPhone.body().path("items").get(0).path("trackingTag").asString())
                .isEqualTo("src=hero&po=" + w.code());

        Res summary = call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/summary", null, null);
        JsonNode k = summary.body().path("kpis");
        assertThat(k.path("preorders").asInt()).isEqualTo(2);
        assertThat(k.path("cancelled").asInt()).isEqualTo(1);
        assertThat(k.path("revenue").asLong()).isEqualTo(4799);
        assertThat(k.path("deliveryRevenue").asLong()).isEqualTo(799);
        assertThat(summary.body().path("ordersTotal").asInt()).isEqualTo(3);
        assertThat(summary.body().path("placements").get(0).path("impressions").asLong()).isZero();
        assertThat(summary.body().path("fulfilmentSplit").path("pickup").asInt()).isEqualTo(1);

        Res csv = call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/exports/orders.csv", null, null);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.contentType()).startsWith("text/csv");
        assertThat(csv.text().split("\r\n")).hasSize(4);
        assertThat(csv.text()).startsWith("Order,Pre-order,Customer,Phone");
        Res filtered = call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/exports/orders-filtered.csv?status=PAID",
                null, null);
        assertThat(filtered.text().split("\r\n")).hasSize(3);
        Res picking = call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/exports/picking-list.csv", null, null);
        assertThat(picking.text()).startsWith("Product,Seller,Units\r\nEgusi,seller-1,");
        assertThat(call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/exports/everything.csv", null, null).status())
                .isEqualTo(422);
        assertThat(outboxPayloads("preorder.export.created", w.id().toString()))
                .anySatisfy(p -> assertThat(p).contains("\"export\": \"orders-filtered\"", "\"status\": \"PAID\"",
                        "\"actor\": \"local-dev\""));

        upstreams.product("EGU", "Pantry", false);
        Res products = call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/products", null, null);
        assertThat(products.body().path("items")).extracting(n -> n.path("atin").asString() + ":"
                + n.path("price").asLong() + ":" + n.path("sellerName").asString()).contains("EGU:1000:Seller One");

        Map<String, Object> late = new LinkedHashMap<>();
        late.put("customerId", "cust-late");
        late.put("addressId", "addr-1");
        late.put("address", Map.of("fullName", "Ada Okafor", "phone", "+1 806 555 0101", "line1", "1 Main St",
                "zip", "79601"));
        late.put("phoneConfirmed", true);
        late.put("items", List.of(Map.of("atin", "EGU", "qty", 2)));
        late.put("payment", "PAYMENT_LINK");
        late.put("reason", "CUSTOMER_CALLED");
        late.put("note", "Called at 9am");
        Res added = call(HttpMethod.POST, "/v1/preorders/" + w.id() + "/late-orders", late, null);
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.body().path("number").asString()).isEqualTo(w.code() + "-L90");
        assertThat(added.body().path("status").asString()).isEqualTo("LATE_ADD");
        assertThat(added.body().path("channel").asString()).isEqualTo("AGENT_LATE");
        assertThat(added.body().path("lines").get(0).path("sellerName").asString()).isEqualTo("seller-1");
        assertThat(added.body().path("ordersStatus").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(added.body().path("paymentLinkUrl").asString()).startsWith("https://pay.test/");
        assertThat(added.body().path("note").asString())
                .startsWith("Customer called · payment link sent by SMS (expires in 24 h) · by local-dev");
        assertThat(upstreams.lateRequests()).last().satisfies(r -> {
            assertThat(r.windowId()).isEqualTo(w.id());
            assertThat(r.address().addressId()).isEqualTo("addr-1");
            assertThat(r.address().city()).isEqualTo("Abilene");
            assertThat(r.address().state()).isEqualTo("TX");
            assertThat(r.address().postalCode()).isEqualTo("79601");
            assertThat(r.fulfilment()).isEqualTo("DOOR");
            assertThat(r.reason()).isEqualTo("Customer called");
            assertThat(r.actorId()).isEqualTo("local-dev");
            assertThat(r.lateAdd().linkExpiryHours()).isEqualTo(24);
        });
        assertThat(outboxTypes(w.id().toString())).contains("preorder.late_order.added");
        JsonNode lateEvent = json.readTree(outboxPayloads("preorder.late_order.added", w.id().toString()).get(0));
        assertThat(lateEvent.path("paymentUrl").asString()).startsWith("https://pay.test/");
        assertThat(lateEvent.path("expiresAt").asString()).isNotBlank();
        assertThat(lateEvent.path("reason").asString()).isEqualTo("Customer called");

        // The order's placed event completes the late add without changing its number or status.
        String lateId = added.body().path("orderId").asString();
        projection.onOrderPlaced(UUID.randomUUID(), placed(lateId, w.id(), "cust-late", "agent",
                PreorderFulfilment.DOOR, 2799, 799, now.plusSeconds(600)));
        Res lateRow = call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/orders?status=LATE_ADD", null, null);
        assertThat(lateRow.body().path("items").get(0).path("number").asString()).isEqualTo(w.code() + "-L90");
        assertThat(lateRow.body().path("items").get(0).path("phone").asString()).isEqualTo("+1 806 555 0100");

        late.put("phoneConfirmed", false);
        Res unconfirmed = call(HttpMethod.POST, "/v1/preorders/" + w.id() + "/late-orders", late, null);
        assertThat(unconfirmed.status()).isEqualTo(422);
        assertThat(unconfirmed.body().path("code").asString()).isEqualTo("delivery-runs.phone-not-confirmed");
        late.put("phoneConfirmed", true);
        upstreams.rejectLateOrders("orders.payment-failed");
        try {
            Res declined = call(HttpMethod.POST, "/v1/preorders/" + w.id() + "/late-orders", late, null);
            assertThat(declined.status()).isEqualTo(422);
            assertThat(declined.body().path("code").asString()).isEqualTo("orders.payment-failed");
        } finally {
            upstreams.rejectLateOrders(null);
        }

        ItSupport.as(new Actor("support-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.customer_support"),
                Set.of("delivery-runs:read", "preorders:late-add")));
        assertThat(call(HttpMethod.GET, "/v1/preorders/" + w.id() + "/exports/orders.csv", null, null).status())
                .isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/v1/preorders/" + w.id() + "/pause", Map.of(), null).status())
                .isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/v1/preorders/" + w.id() + "/late-orders", late, null).status())
                .isEqualTo(201);
        assertThat(call(HttpMethod.GET, "/v1/service/preorders/eligibility?country=US&region=TX&atins=EGU", null,
                null).status()).isEqualTo(403);
    }

    @Test
    void schedulerSendsCommsClosesHandsOrdersToARunAndMarksDelivery() {
        Location bastrop = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL));
        settings.addArea("US/TX/Bastrop");
        Instant now = clock.instant();
        Instant opens = now.plus(Duration.ofHours(1));
        Instant closes = now.plus(Duration.ofDays(3));
        PreorderWindow w = publish("Bastrop Pre-order #1", "US/TX/Bastrop", opens, closes);
        assertThat(w.details().comms()).contains(CommKind.ANNOUNCE, CommKind.REMIND_48H);

        assertThat(scheduler.process(w.id())).isZero();
        clock.set(opens.plusSeconds(60));
        assertThat(scheduler.candidates(100)).contains(w.id());
        assertThat(scheduler.process(w.id())).isEqualTo(2);
        assertThat(scheduler.process(w.id())).as("each step once").isZero();
        clock.set(closes.minus(Duration.ofHours(47)));
        assertThat(scheduler.process(w.id())).isEqualTo(1);
        clock.set(closes.minus(Duration.ofHours(5)));
        assertThat(scheduler.process(w.id())).isEqualTo(1);

        String order = UUID.randomUUID().toString();
        projection.onOrderPlaced(UUID.randomUUID(), placed(order, w.id(), "cust-1", "sms", PreorderFulfilment.DOOR,
                2799, 799, clock.instant()));

        // Audience: the location's subscribers with consent on the channel, past buyers, and the waitlist.
        ItSupport.as(new Actor("shopper-9", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of()));
        subscriptions.subscribeMine(bastrop.id(), Set.of(Channel.EMAIL, Channel.SMS), "location-page");
        ItSupport.reset();
        assertThat(call(HttpMethod.POST, "/v1/public/subscriptions", Map.of("email", "Wait@Example.test", "area",
                "US/TX/Bastrop", "source", "preorder-waitlist"), null).body().path("status").asString())
                .isEqualTo("WAITLISTED");
        assertThat(call(HttpMethod.POST, "/v1/public/subscriptions", Map.of("email", "texas@example.test",
                "country", "US", "region", "TX"), null).status()).isEqualTo(202);
        call(HttpMethod.POST, "/v1/public/subscriptions", Map.of("email", "atl@example.test", "area",
                "US/GA/Atlanta"), null);
        Res subs = call(HttpMethod.GET, "/v1/service/preorders/" + w.id() + "/audience?channel=EMAIL", null, null);
        assertThat(subs.status()).isEqualTo(200);
        assertThat(subs.body().path("members")).extracting(m -> m.path("userId").asString())
                .containsExactlyInAnyOrder("shopper-9", "cust-1");
        assertThat(subs.body().path("members")).filteredOn(m -> m.path("userId").asString().equals("cust-1"))
                .extracting(m -> m.path("subscriptionId").asString() + "|" + m.path("phone").asString())
                .containsExactly("buyer:cust-1|+1 806 555 0100");
        Res firstPage = call(HttpMethod.GET, "/v1/service/preorders/" + w.id() + "/audience?channel=EMAIL&limit=1",
                null, null);
        assertThat(firstPage.body().path("members")).hasSize(1);
        Res secondPage = call(HttpMethod.GET, "/v1/service/preorders/" + w.id() + "/audience?channel=EMAIL&limit=1"
                + "&after=" + firstPage.body().path("next").asString(), null, null);
        assertThat(secondPage.body().path("members")).hasSize(1);
        assertThat(secondPage.body().path("next").isNull()).isTrue();
        Res waiting = call(HttpMethod.GET, "/v1/service/preorders/" + w.id() + "/audience?channel=EMAIL"
                + "&segment=WAITLIST", null, null);
        assertThat(waiting.body().path("members")).extracting(m -> m.path("email").asString())
                .containsExactlyInAnyOrder("wait@example.test", "texas@example.test");
        assertThat(waiting.body().path("members").get(0).path("unsubscribeUrl").asString())
                .startsWith("http://storefront.test/pre-orders/waitlist/unsubscribe?token=");
        String token = jdbc.sql("select unsubscribe_token from preorder_waitlist where email = 'wait@example.test'")
                .query(String.class).single();
        assertThat(call(HttpMethod.POST, "/v1/public/preorders/waitlist/unsubscribe", Map.of("token", token), null)
                .status()).isEqualTo(204);
        assertThat(call(HttpMethod.GET, "/v1/service/preorders/" + w.id() + "/audience?channel=EMAIL"
                + "&segment=WAITLIST", null, null).body().path("members")).hasSize(1);
        assertThat(call(HttpMethod.GET, "/v1/service/preorders/" + w.id() + "/audience?channel=SMS"
                + "&segment=WAITLIST", null, null).body().path("members")).isEmpty();

        clock.set(closes.plusSeconds(60));
        assertThat(scheduler.process(w.id())).isEqualTo(2);
        PreorderWindow closed = windowRepository.findById(w.id()).orElseThrow();
        assertThat(closed.runId()).isNotNull();
        Run run = runs.get(closed.runId());
        assertThat(run.status()).isEqualTo(RunStatus.CONFIRMED);
        assertThat(run.locationId()).isEqualTo(bastrop.id());
        assertThat(run.reservedOrders()).isEqualTo(1);
        assertThat(run.terms().feeMinor()).isEqualTo(799);
        assertThat(run.terms().window().start().atZone(CHICAGO).toLocalDate()).isEqualTo(w.details().deliversOn());
        assertThat(runs.orders(run.id(), RunOrderStatus.CONFIRMED)).extracting(o -> o.orderId())
                .containsExactly(order);
        assertThat(runs.fromWindow(w.id())).contains(run);

        assertThat(outboxPayloads("preorder.comms.due", w.id().toString()))
                .extracting(p -> json.readTree(p).path("kind").asString())
                .containsExactly("ANNOUNCE", "REMIND_48H", "LAST_CALL_6H", "CLOSED");
        JsonNode announce = json.readTree(outboxPayloads("preorder.comms.due", w.id().toString()).get(0));
        assertThat(announce.path("smsCopy").asString()).isEqualTo("AfroTransact: pre-order open");
        assertThat(announce.path("links").path("sms").asString()).endsWith("src=sms&po=" + w.code());
        assertThat(announce.path("doorFeeMinor").asLong()).isEqualTo(799);
        assertThat(outboxTypes(w.id().toString())).contains("preorder.window.opened", "preorder.window.closed");
        assertThat(outboxTypes(run.id().toString())).contains("delivery-runs.run.confirmed");
        JsonNode closedComms = json.readTree(outboxPayloads("preorder.comms.due", w.id().toString()).get(3));
        assertThat(closedComms.path("orders")).extracting(o -> o.path("orderId").asString()).containsExactly(order);
        assertThat(closedComms.path("deliversDisplay").asString()).isNotBlank();

        clock.set(closed.deliveredAt().plusSeconds(1));
        assertThat(scheduler.process(w.id())).isEqualTo(1);
        assertThat(outboxTypes(w.id().toString())).contains("preorder.window.delivered");
        assertThat(scheduler.candidates(100)).doesNotContain(w.id());
    }

    @Test
    void pausedWindowsSkipCommsAndDemoAreasSeedOnce() {
        settings.addArea("US/TX/Waco");
        Instant now = clock.instant();
        PreorderWindow w = publish("Waco Pre-order #1", "US/TX/Waco", now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(1)));
        WindowView paused = windows.pause(w.id(), w.version());
        assertThat(paused.status().name()).isEqualTo("PAUSED");
        assertThat(scheduler.process(w.id())).as("opened marker only, no announcement").isEqualTo(1);
        assertThat(outboxTypes(w.id().toString())).doesNotContain("preorder.comms.due", "preorder.window.opened");
        windows.resume(w.id(), paused.window().version());
        assertThat(scheduler.process(w.id())).as("announce and the 48 h reminder after resume, still open")
                .isEqualTo(2);
        assertThat(outboxTypes(w.id().toString())).contains("preorder.window.paused", "preorder.window.resumed",
                "preorder.comms.due");

        settings.seedDemoAreas();
        assertThat(settings.seedDemoAreas()).isZero();
        assertThat(call(HttpMethod.GET, "/v1/preorder-areas?country=US&region=TX&q=laredo", null, null).body()
                .path("rows").get(0).path("on").asInt()).isZero();

        ItSupport.as(new Actor("viewer", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("delivery-runs:read")));
        assertThatThrownBy(() -> windows.pause(w.id(), null)).isInstanceOf(ForbiddenActionException.class);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private PreorderWindow publish(String name, String target, Instant opens, Instant closes) {
        LocalDate delivers = closes.plus(Duration.ofDays(2)).atZone(CHICAGO).toLocalDate();
        WindowView v = windows.create(new WindowCommand(name, List.of(target), opens, closes, delivers,
                "America/Chicago", name + " is open", "Fresh egusi delivered", "AfroTransact: pre-order open",
                Set.of("Pantry", "Spices"), true, true, null, null, true, 20_000L, Set.of(CommKind.values()), null,
                null, null));
        WindowView withHero = windows.setCreative(v.window().id(), CreativeSlot.HERO, upstreams.image(1920, 640),
                v.window().version());
        return windows.publish(v.window().id(), withHero.window().version()).window();
    }

    private static OrderPlaced placed(String orderId, UUID windowId, String customer, String src,
                                      PreorderFulfilment fulfilment, long total, long fee, Instant at) {
        return new OrderPlaced(orderId, "AT-" + orderId.substring(0, 6), customer, "USD", at, "Shopper " + customer,
                "+1 806 555 0100", customer + "@example.test", "1 Main St", "Amarillo", "TX", "79101", "Gate code 12",
                src, null, null, List.of(new OrderPlaced.Package("shp-" + orderId, "seller-1", windowId, fulfilment,
                total, fee, List.of(new PreorderLine("EGU", "Egusi", "seller-1", 2)))));
    }

    private Map<String, Object> windowBody(String name, String target, Instant opens, Instant closes) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("targets", List.of(target));
        b.put("opensAt", opens.toString());
        b.put("closesAt", closes.toString());
        b.put("deliversOn", closes.plus(Duration.ofDays(3)).atZone(CHICAGO).toLocalDate().toString());
        b.put("timeZone", "America/Chicago");
        b.put("ribbon", "Lubbock pre-order is open");
        b.put("headline", "Fresh egusi, garri and palm oil");
        b.put("smsCopy", "AfroTransact: pre-order open. Reply STOP to opt out");
        b.put("categories", List.of("Pantry", "Spices"));
        b.put("door", true);
        b.put("pickup", true);
        b.put("comms", List.of("ANNOUNCE", "REMIND_48H"));
        return b;
    }

    private List<String> outboxTypes(String aggregateId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, type")
                .param("id", aggregateId).query(String.class).list();
    }

    private List<String> outboxPayloads(String type, String aggregateId) {
        return jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                        + "order by occurred_at")
                .param("t", type).param("id", aggregateId).query(String.class).list();
    }

    /** A call with an already-encoded path (no template expansion or re-encoding). */
    private Res callRaw(HttpMethod method, String rawPath) {
        return http.method(method).uri(URI.create(base + rawPath)).exchange((req, res) -> read(
                res.getStatusCode().value(), res.getBody().readAllBytes(), res.getHeaders().getContentType(),
                res.getHeaders().getETag()));
    }

    private Res call(HttpMethod method, String uri, Object body, String ifMatch) {
        RestClient.RequestBodySpec spec = http.method(method).uri(uri);
        if (ifMatch != null) {
            spec = spec.header("If-Match", ifMatch);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes(),
                res.getHeaders().getContentType(), res.getHeaders().getETag()));
    }

    private Res read(int status, byte[] bytes, MediaType type, String etag) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (RuntimeException e) {
            node = json.createObjectNode();
        }
        return new Res(status, node, type == null ? "" : type.toString(), text, etag);
    }
}
