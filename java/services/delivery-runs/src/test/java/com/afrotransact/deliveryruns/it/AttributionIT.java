package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.deliveryruns.application.AdvertorialCommand;
import com.afrotransact.deliveryruns.application.AdvertorialService;
import com.afrotransact.deliveryruns.application.AdvertorialView;
import com.afrotransact.deliveryruns.application.AttributionService;
import com.afrotransact.deliveryruns.application.AttributionService.Report;
import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.ReserveCommand;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.application.RunSlotService;
import com.afrotransact.deliveryruns.application.SubscriptionService;
import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.FeaturedProduct;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.UtmTags;
import com.afrotransact.deliveryruns.port.AttributionRepository.Row;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S262 / S263 remainders against real PostgreSQL: the orders of a run are credited to the advertorial that led to them,
 * tagged visits and sign-ups are counted per location, run and UTM tags (privacy-safe counters with a per-day cap), a
 * location advertorial features up to four products, and staff switch a location on and off with an audited reason.
 * Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false",
    "at.delivery-runs.geo.base-url=http://127.0.0.1:1"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class AttributionIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    private static final Pattern TOKEN = Pattern.compile("confirm\\?token=([A-Za-z0-9_%-]+)");

    private final LocationService locations;
    private final RunService runs;
    private final RunSlotService slots;
    private final SubscriptionService subscriptions;
    private final AttributionService attribution;
    private final AdvertorialService advertorials;
    private final MutableClock clock;
    private final JdbcClient jdbc;

    @Autowired
    AttributionIT(LocationService locations, RunService runs, RunSlotService slots, SubscriptionService subscriptions,
                  AttributionService attribution, AdvertorialService advertorials, MutableClock clock,
                  JdbcClient jdbc) {
        this.locations = locations;
        this.runs = runs;
        this.slots = slots;
        this.subscriptions = subscriptions;
        this.attribution = attribution;
        this.advertorials = advertorials;
        this.clock = clock;
        this.jdbc = jdbc;
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
    void resetActor() {
        ItSupport.reset();
    }

    @Test
    void taggedVisitsAreCountedPerLocationAndRunAndUntaggedOnesAreNot() {
        Location bastrop = location();
        Run run = openRun(bastrop);
        UtmTags whatsapp = UtmTags.clean("WhatsApp", "social", "delivery-run-bastrop-20261024");

        attribution.visit(bastrop.slug(), null, whatsapp);
        attribution.visit(bastrop.slug(), run.id(), whatsapp);
        attribution.visit(bastrop.slug(), run.id(), UtmTags.clean("email", "email", "delivery-run-bastrop-20261024"));
        attribution.visit(bastrop.slug(), run.id(), UtmTags.clean(null, null, null));
        attribution.visit(bastrop.slug(), UUID.randomUUID(), whatsapp);

        Report all = attribution.report(bastrop.id(), null, 30);
        assertThat(all.visits()).as("the untagged visit is not counted").isEqualTo(4);
        assertThat(all.rows()).extracting(r -> r.tags().source() + "/" + r.visits())
                .containsExactly("whatsapp/3", "email/1");
        Report one = attribution.report(bastrop.id(), run.id(), 30);
        assertThat(one.rows()).extracting(r -> r.tags().source() + "/" + r.visits())
                .containsExactlyInAnyOrder("whatsapp/1", "email/1");
        assertThat(one.signups()).isZero();

        assertThatThrownBy(() -> attribution.visit("nowhere", null, whatsapp)).isInstanceOf(NotFoundException.class);
        runs.cancel(run.id(), "Storm warning");
        locations.deactivate(bastrop.id(), "Paused for the winter");
        assertThatThrownBy(() -> attribution.visit(bastrop.slug(), null, whatsapp))
                .as("an inactive location takes no visits").isInstanceOf(NotFoundException.class);
    }

    @Test
    void madeUpTagsCannotFillTheCountersBeyondTheDailyLimit() {
        Location bastrop = location();
        for (int i = 0; i < 100; i++) {
            attribution.visit(bastrop.slug(), null, UtmTags.clean("src" + i, "social", "camp"));
        }
        attribution.visit(bastrop.slug(), null, UtmTags.clean("one-too-many", "social", "camp"));
        attribution.visit(bastrop.slug(), null, UtmTags.clean("src3", "social", "camp"));
        attribution.visit(bastrop.slug(), null, UtmTags.clean("Not a token!", "social", "camp"));

        Report all = attribution.report(bastrop.id(), null, 1);
        assertThat(all.rows()).hasSize(101);
        assertThat(all.rows()).filteredOn(r -> "other".equals(r.tags().source())).extracting(Row::visits)
                .as("the 101st combination and the unusable tag share the overflow bucket").containsExactly(2L);
        assertThat(all.rows()).filteredOn(r -> "src3".equals(r.tags().source())).extracting(Row::visits)
                .containsExactly(2L);
        assertThat(jdbc.sql("select count(*) from run_attribution_stat where location_id = :l").param("l", bastrop.id())
                .query(Long.class).single()).isEqualTo(101);
    }

    @Test
    void anEmailSignUpCountsWhenItIsConfirmedAndASignedInOneAtOnce() {
        Location bastrop = location();
        Run run = openRun(bastrop);
        UtmTags tags = UtmTags.clean("sms", "sms", "delivery-run-bastrop-20261024");

        subscriptions.subscribeEmail(bastrop.id(), "ada@example.com", "location-page", tags, run.id());
        assertThat(attribution.report(bastrop.id(), run.id(), 7).signups()).as("not before it is confirmed").isZero();
        UUID id = jdbc.sql("select id from subscription where email = 'ada@example.com' and location_id = :l")
                .param("l", bastrop.id()).query(UUID.class).single();
        String created = jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id and type = "
                + "'delivery-runs.subscription.created'").param("id", id.toString()).query(String.class).single();
        Matcher m = TOKEN.matcher(created);
        assertThat(m.find()).isTrue();
        String token = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        subscriptions.confirm(token);
        subscriptions.confirm(token);
        Report afterConfirm = attribution.report(bastrop.id(), run.id(), 7);
        assertThat(afterConfirm.signups()).as("confirming twice counts once").isEqualTo(1);
        assertThat(afterConfirm.rows()).singleElement().satisfies(r -> {
            assertThat(r.tags()).isEqualTo(tags);
            assertThat(r.signups()).isEqualTo(1);
            assertThat(r.visits()).isZero();
        });
        assertThat(jdbc.sql("select count(*) from subscription_attribution where subscription_id = :id").param("id", id)
                .query(Long.class).single())
                .as("nothing stays behind once counted").isZero();

        ItSupport.as(new Actor("shopper-42", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of()));
        subscriptions.subscribeMine(bastrop.id(), EnumSet.of(Channel.PUSH), "location-page",
                UtmTags.clean("facebook", "social", "delivery-run-bastrop"), null);
        subscriptions.subscribeMine(bastrop.id(), EnumSet.of(Channel.SMS), "location-page",
                UtmTags.clean("facebook", "social", "delivery-run-bastrop"), null);
        ItSupport.reset();
        Report all = attribution.report(bastrop.id(), null, 7);
        assertThat(all.signups()).as("adding a channel is not a second sign-up").isEqualTo(2);

        subscriptions.subscribeEmail(bastrop.id(), "grace@example.com", "footer");
        assertThat(attribution.report(bastrop.id(), null, 7).signups()).as("untagged: nothing to attribute")
                .isEqualTo(2);
    }

    @Test
    void ordersOfARunAreCreditedToTheAdvertorialThatLedToThemAndStopCountingWhenRefunded() {
        Location bastrop = location();
        Run run = openRun(bastrop);
        AdvertorialView generated = advertorials.generate(AdvertorialSource.RUN, run.id());
        String creative = generated.advertorial().creative();
        assertThat(generated.results().orders()).isZero();

        String first = id("ord-adv-1");
        String second = id("ord-adv-2");
        String plain = id("ord-plain");
        slots.reserve(new ReserveCommand(run.id(), first, "cust-1", 1, true));
        slots.reserve(new ReserveCommand(run.id(), second, "cust-2", 1, true));
        slots.reserve(new ReserveCommand(run.id(), plain, "cust-3", 1, true));
        // The placed event may arrive before or after the slot is taken: the join happens when staff look.
        attribution.orderPlaced(first, creative, "page", clock.instant());
        attribution.orderPlaced(first, creative, "page", clock.instant());
        attribution.orderPlaced(second, creative, "page", clock.instant());
        attribution.orderPlaced(id("ord-not-on-the-run"), creative, "page", clock.instant());
        attribution.orderPlaced(id("ord-other"), "story", "ig", clock.instant());
        attribution.orderPlaced(plain, null, null, clock.instant());

        assertThat(advertorials.get(generated.advertorial().id()).results().orders())
                .as("two orders hold a slot on the run and carry the creative").isEqualTo(2);

        runs.cancel(run.id(), "Storm warning");
        assertThat(advertorials.get(generated.advertorial().id()).results().orders())
                .as("a cancelled run refunds its orders, so none count").isZero();
    }

    @Test
    void anAdvertorialFeaturesUpToFourProductsAndKeepsThemWhenEditedWithoutSayingSo() {
        Location bastrop = location();
        Run run = openRun(bastrop);
        List<FeaturedProduct> two = List.of(
                new FeaturedProduct("ATSHEA0001", "Raw shea butter", "https://img.test/shea.jpg"),
                new FeaturedProduct("ATGARI0001", "Garri, 2 kg", null));
        AdvertorialView created = advertorials.create(new AdvertorialCommand(AdvertorialSource.RUN, run.id(), "B", 1,
                0, "Fresh from Lagos", "Order by Thursday", "Order now", null, null, two));
        assertThat(created.advertorial().featured()).containsExactlyElementsOf(two);
        assertThat(advertorials.get(created.advertorial().id()).advertorial().featured())
                .as("they survive a round trip through the database").containsExactlyElementsOf(two);

        AdvertorialView kept = advertorials.update(created.advertorial().id(), new AdvertorialCommand(null, null, null,
                null, null, "Fresh from Accra", "Order by Thursday", "Order now", null, null),
                created.advertorial().version());
        assertThat(kept.advertorial().featured()).as("an edit that says nothing keeps them").hasSize(2);

        AdvertorialView swapped = advertorials.update(created.advertorial().id(), new AdvertorialCommand(null, null,
                null, null, null, "Fresh from Accra", "Order by Thursday", "Order now", null, null,
                List.of(two.get(1))), kept.advertorial().version());
        assertThat(swapped.advertorial().featured()).containsExactly(two.get(1));
        AdvertorialView cleared = advertorials.update(created.advertorial().id(), new AdvertorialCommand(null, null,
                null, null, null, "Fresh from Accra", "Order by Thursday", "Order now", null, null, List.of()),
                swapped.advertorial().version());
        assertThat(cleared.advertorial().featured()).isEmpty();

        List<FeaturedProduct> five = List.of(product(1), product(2), product(3), product(4), product(5));
        assertThatThrownBy(() -> advertorials.create(new AdvertorialCommand(AdvertorialSource.RUN, run.id(), "C", 1,
                0, "Too many", "", "Order now", null, null, five))).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("at most 4");
    }

    @Test
    void switchingALocationOffNeedsNoLiveRunsIsAuditedWithItsReasonAndAnEditNeverSwitchesIt() {
        Location bastrop = location();
        Run run = openRun(bastrop);
        assertThatThrownBy(() -> locations.deactivate(bastrop.id(), "Seasonal"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("1 run");
        assertThat(locations.get(bastrop.id()).active()).isTrue();

        runs.cancel(run.id(), "Storm warning");
        Location off = locations.deactivate(bastrop.id(), "  Seasonal pause  ");
        assertThat(off.active()).isFalse();
        assertThat(auditPayloads(bastrop.id())).anySatisfy(p -> assertThat(p).contains("\"action\": \"CREATED\""))
                .anySatisfy(p -> assertThat(p).contains("\"action\": \"DEACTIVATED\"",
                        "\"reason\": \"Seasonal pause\"", "\"actorId\": \"local-dev\"", "\"active\": false"));
        assertThat(locations.deactivate(bastrop.id(), "Again").active()).as("already off: unchanged").isFalse();
        assertThat(auditPayloads(bastrop.id())).as("no second DEACTIVATED entry").filteredOn(
                p -> p.contains("DEACTIVATED")).hasSize(1);

        Location edited = locations.update(bastrop.id(), Fixtures.bastrop(bastrop.slug(), DropMode.DOORSTEP, 5,
                CutoffPolicy.EXTEND));
        assertThat(edited.active()).as("an edit does not switch a location back on").isFalse();
        assertThat(edited.details().defaultCapacity().maxOrders()).isEqualTo(5);

        Location on = locations.activate(bastrop.id(), null);
        assertThat(on.active()).isTrue();
        assertThat(auditPayloads(bastrop.id())).anySatisfy(p -> assertThat(p).contains("\"action\": \"ACTIVATED\"")
                .doesNotContain("\"reason\": \"")).anySatisfy(p -> assertThat(p).contains("\"action\": \"UPDATED\""));
        assertThat(locations.activate(bastrop.id(), "no-op").active()).isTrue();

        assertThatThrownBy(() -> locations.deactivate(bastrop.id(), "x".repeat(501))).isInstanceOf(RuleViolation.class);
        ItSupport.as(new Actor("viewer", PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of("delivery-runs:read")));
        assertThatThrownBy(() -> locations.deactivate(bastrop.id(), "Not allowed")).hasMessageContaining(
                "delivery-runs:manage");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private Location location() {
        return locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND));
    }

    private Run openRun(Location location) {
        Run draft = runs.create(Fixtures.run(location.id(), clock.instant(), Duration.ofDays(3), 2));
        runs.announce(draft.id());
        return runs.open(draft.id());
    }

    /** Order ids are unique in production; keep them so when a test database is reused. */
    private static String id(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static FeaturedProduct product(int n) {
        return new FeaturedProduct("ATPROD000" + n, "Product " + n, null);
    }

    private List<String> auditPayloads(UUID locationId) {
        return jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id and type = "
                + "'delivery-runs.location.changed' order by occurred_at").param("id", locationId.toString())
                .query(String.class).list();
    }
}
