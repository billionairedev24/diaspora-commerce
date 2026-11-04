package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.deliveryruns.application.AudiencePage;
import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.SubscriptionService;
import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Subscription;
import com.afrotransact.deliveryruns.domain.SubscriptionStatus;
import com.afrotransact.deliveryruns.port.SubscriberCounts;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
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

/** Location subscriptions: double opt-in, per-channel consent records, unsubscribe links, counts and audience. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class SubscriptionIT {

    /** Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker. */
    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    private static final Pattern TOKEN = Pattern.compile("confirm\\?token=([A-Za-z0-9_%-]+)");

    private final LocationService locations;
    private final SubscriptionService subscriptions;
    private final MutableClock clock;
    private final JdbcClient jdbc;

    @Autowired
    SubscriptionIT(LocationService locations, SubscriptionService subscriptions, MutableClock clock, JdbcClient jdbc) {
        this.locations = locations;
        this.subscriptions = subscriptions;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void now() {
        clock.set(Instant.parse("2026-10-01T12:00:00Z"));
    }

    @AfterEach
    void resetActor() {
        ItSupport.reset();
    }

    @Test
    void emailSignUpIsConfirmedByDoubleOptInThenUnsubscribedByLink() {
        Location bastrop = location();
        subscriptions.subscribeEmail(bastrop.id(), "Ada@Example.com", "location-page");
        UUID id = jdbc.sql("select id from subscription where location_id = :l and email = 'ada@example.com'")
                .param("l", bastrop.id()).query(UUID.class).single();
        String created = payload("delivery-runs.subscription.created", id);
        assertThat(created).contains("\"status\": \"PENDING_CONFIRMATION\"", "\"email\": \"ada@example.com\"",
                "\"confirmUrl\": \"http://storefront.test/delivery-runs/subscriptions/confirm?token=",
                "\"unsubscribeUrl\": \"http://storefront.test/delivery-runs/subscriptions/unsubscribe?token=",
                "\"whatsappInviteUrl\": \"https://chat.whatsapp.com/BastropRuns\"", "\"changedChannels\": [\"EMAIL\"]");
        assertThat(counts(bastrop).pending()).isEqualTo(1);
        assertThat(counts(bastrop).byChannel().get(Channel.EMAIL)).isZero();

        String token = token(created);
        clock.advance(Duration.ofMinutes(5));
        Subscription active = subscriptions.confirm(token);
        assertThat(active.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscriptions.confirm(token).id()).as("confirming twice is harmless").isEqualTo(id);
        assertThat(types(id)).containsExactly("delivery-runs.subscription.created",
                "delivery-runs.subscription.confirmed");
        assertThat(counts(bastrop).byChannel().get(Channel.EMAIL)).isEqualTo(1);

        subscriptions.subscribeEmail(bastrop.id(), "ada@example.com", "footer");
        assertThat(types(id)).as("an active address gets no new confirmation").hasSize(2);

        AudiencePage audience = subscriptions.audience(bastrop.id(), Channel.EMAIL, null, 10);
        assertThat(audience.members()).extracting(AudiencePage.Member::email).containsExactly("ada@example.com");
        String unsubscribeUrl = audience.members().getFirst().unsubscribeUrl();
        String unsubscribeToken = URLDecoder.decode(unsubscribeUrl.substring(unsubscribeUrl.indexOf("token=") + 6),
                StandardCharsets.UTF_8);

        clock.advance(Duration.ofDays(3));
        Subscription cancelled = subscriptions.unsubscribe(unsubscribeToken, Set.of());
        assertThat(cancelled.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(payload("delivery-runs.subscription.cancelled", id)).contains("\"status\": \"CANCELLED\"",
                "\"changedChannels\": [\"EMAIL\"]", "\"channels\": []");
        assertThat(consentLog(id)).containsExactly("EMAIL GRANTED location-page", "EMAIL CONFIRMED double-opt-in",
                "EMAIL WITHDRAWN location-page");
        assertThatThrownBy(() -> subscriptions.unsubscribe("nope", Set.of())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void expiredConfirmationLinksAreRefusedAndASecondSignUpSendsANewOne() {
        Location bastrop = location();
        subscriptions.subscribeEmail(bastrop.id(), "late@example.com", "page");
        UUID id = jdbc.sql("select id from subscription where location_id = :l").param("l", bastrop.id())
                .query(UUID.class).single();
        String first = token(payload("delivery-runs.subscription.created", id));
        clock.advance(Duration.ofDays(4));
        assertThatThrownBy(() -> subscriptions.confirm(first)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.confirmation-expired");

        clock.advance(Duration.ofMinutes(1));
        subscriptions.subscribeEmail(bastrop.id(), "late@example.com", "page");
        String second = token(payload("delivery-runs.subscription.created", id));
        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(() -> subscriptions.confirm(first)).isInstanceOf(NotFoundException.class);
        assertThat(subscriptions.confirm(second).status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void shoppersChooseChannelsAndWithdrawThemOneByOne() {
        Location bastrop = location();
        ItSupport.as(new Actor("shopper-42", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of()));
        Subscription s = subscriptions.subscribeMine(bastrop.id(), EnumSet.of(Channel.WHATSAPP, Channel.PUSH),
                "location-page");
        assertThat(s.activeChannels()).containsExactlyInAnyOrder(Channel.WHATSAPP, Channel.PUSH);
        clock.advance(Duration.ofMinutes(1));
        Subscription more = subscriptions.subscribeMine(bastrop.id(), EnumSet.of(Channel.SMS, Channel.PUSH),
                "checkout");
        assertThat(more.id()).isEqualTo(s.id());
        assertThat(more.activeChannels()).containsExactlyInAnyOrder(Channel.WHATSAPP, Channel.PUSH, Channel.SMS);
        assertThat(more.consents().get(Channel.PUSH).source()).as("existing consent kept").isEqualTo("location-page");
        assertThat(payload("delivery-runs.subscription.created", s.id())).contains("\"changedChannels\": [\"SMS\"]",
                "\"userId\": \"shopper-42\"");

        clock.advance(Duration.ofMinutes(1));
        Subscription lessWhatsapp = subscriptions.unsubscribeMine(bastrop.id(), Set.of(Channel.WHATSAPP));
        assertThat(lessWhatsapp.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(lessWhatsapp.activeChannels()).containsExactlyInAnyOrder(Channel.PUSH, Channel.SMS);
        assertThat(subscriptions.mine()).extracting(Subscription::id).contains(s.id());

        ItSupport.reset();
        SubscriberCounts c = counts(bastrop);
        assertThat(c.active()).isEqualTo(1);
        assertThat(c.byChannel()).containsEntry(Channel.PUSH, 1L).containsEntry(Channel.SMS, 1L)
                .containsEntry(Channel.WHATSAPP, 0L).containsEntry(Channel.EMAIL, 0L);
        assertThat(subscriptions.audience(bastrop.id(), Channel.WHATSAPP, null, 10).members()).isEmpty();
        assertThat(subscriptions.audience(bastrop.id(), Channel.SMS, null, 10).members())
                .extracting(AudiencePage.Member::userId).containsExactly("shopper-42");
        assertThat(consentLog(s.id())).containsExactly("PUSH GRANTED location-page",
                "WHATSAPP GRANTED location-page", "SMS GRANTED checkout", "WHATSAPP WITHDRAWN location-page");
    }

    @Test
    void anonymousSubscribersCannotChooseSmsAndAudienceNeedsItsPermission() {
        Location bastrop = location();
        ItSupport.as(new Actor("shopper-7", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of()));
        assertThatThrownBy(() -> subscriptions.audience(bastrop.id(), Channel.EMAIL, null, 10))
                .hasMessageContaining("delivery-runs:audience.read");
        ItSupport.as(new Actor("notifications-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("delivery-runs:audience.read")));
        assertThat(subscriptions.audience(bastrop.id(), Channel.EMAIL, null, 10).members()).isEmpty();
        assertThatThrownBy(() -> subscriptions.subscribeEmail(bastrop.id(), "not-an-email", "page"))
                .isInstanceOf(RuleViolation.class);
    }

    private Location location() {
        return locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND));
    }

    private SubscriberCounts counts(Location l) {
        List<SubscriberCounts> all = subscriptions.counts(l.id());
        return all.getFirst();
    }

    private static String token(String payload) {
        Matcher m = TOKEN.matcher(payload);
        assertThat(m.find()).isTrue();
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private List<String> types(UUID id) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at")
                .param("id", id.toString()).query(String.class).list();
    }

    private List<String> consentLog(UUID id) {
        return jdbc.sql("select channel || ' ' || action || ' ' || source from consent_log "
                        + "where subscription_id = :id order by at, id")
                .param("id", id).query(String.class).list();
    }

    private String payload(String type, UUID id) {
        return jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                        + "order by occurred_at desc limit 1")
                .param("t", type).param("id", id.toString()).query(String.class).single();
    }
}
