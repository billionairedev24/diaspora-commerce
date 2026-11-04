package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.afrotransact.identity.application.OrgReconcileService;
import com.afrotransact.identity.application.TokenClaimsService;
import com.afrotransact.identity.port.SellerOrgSource;
import com.afrotransact.identity.port.SellerOrgSource.SellerOrgPage;
import com.afrotransact.identity.port.SellerOrgSource.SellerOrgState;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Identity's copy of seller organisations against a real broker and PostgreSQL: the approval event consumed once
 * however often it is delivered, a late event never rolling a newer copy back, and the reconcile repairing an
 * approval identity never received (organisation and owner membership), so the owner's next token carries it.
 */
@SpringBootTest(properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=true",
    "at.identity.seller-sync.enabled=false",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
@Import(SellerOrgSyncIT.FakeSeller.class)
class SellerOrgSyncIT {

    /* Left to Ryuk (not stopped per class): the cached context keeps its consumer polling until the JVM exits. */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private static final String TOPIC = "at.seller-org.events";

    /** The seller service's state as identity's reconcile reads it. */
    @TestConfiguration
    static class FakeSeller {

        static final List<SellerOrgState> ORGS = new ArrayList<>();

        @Bean
        @Primary
        SellerOrgSource fakeSellerOrgSource() {
            return new SellerOrgSource() {
                @Override
                public SellerOrgPage approvedOrSuspended(int page, int size) {
                    List<SellerOrgState> all = List.copyOf(ORGS);
                    return new SellerOrgPage(all.stream().skip((long) page * size).limit(size).toList(), all.size());
                }

                @Override
                public Optional<SellerOrgState> org(String orgId) {
                    return ORGS.stream().filter(o -> o.orgId().equals(orgId)).findFirst();
                }
            };
        }
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final OrgReconcileService reconcile;
    private final TokenClaimsService claims;

    @Autowired
    SellerOrgSyncIT(JdbcClient jdbc, KafkaTemplate<String, String> kafka, OrgReconcileService reconcile,
                    TokenClaimsService claims) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.reconcile = reconcile;
        this.claims = claims;
    }

    private static String orgId() {
        return "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private UUID user(String first) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into user_account (id, first_name, last_name, email, email_verified, enabled, sms_consent,
                                          created_at, updated_at, version)
                values (:id, :first, 'Ade', :email, true, true, false, :now, :now, 0)
                """).params(Map.of("id", id, "first", first, "email", id + "@example.com", "now", now)).update();
        jdbc.sql("insert into user_role (user_id, role_key, granted_by) values (:id, 'account.customer', 'it')")
                .param("id", id).update();
        return id;
    }

    private void send(String type, UUID eventId, String orgId, String status, UUID applicant, Instant at)
            throws Exception {
        String payload = """
                {"orgId":"%s","kind":"SELLER","primaryKind":"SELLER","legalName":"Malo Branding LLC",
                 "displayName":"Malo Branding","country":"US","status":"%s","ownerUserIds":["%s"],
                 "applicantUserId":%s,"occurredAt":"%s"}
                """.formatted(orgId, status, applicant, type.endsWith("approved") ? "\"" + applicant + "\"" : "null",
                at);
        ProducerRecord<String, String> rec = new ProducerRecord<>(TOPIC, orgId, payload);
        rec.headers().add(new RecordHeader("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event-type", type.getBytes(StandardCharsets.UTF_8)));
        kafka.send(rec).get();
    }

    private static <T> T eventually(Supplier<T> read, Predicate<T> done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        T value = read.get();
        while (!done.test(value) && System.nanoTime() < deadline) {
            Thread.sleep(250);
            value = read.get();
        }
        return value;
    }

    private Optional<String> status(String orgId) {
        return jdbc.sql("select status from organization where id = :o").param("o", orgId).query(String.class)
                .optional();
    }

    private List<String> roles(String orgId, UUID user) {
        return jdbc.sql("select role_key from org_membership where org_id = :o and user_id = :u")
                .params(Map.of("o", orgId, "u", user)).query(String.class).list();
    }

    private long inbox(UUID eventId) {
        return jdbc.sql("select count(*) from platform_inbox where event_id = :e").param("e", eventId)
                .query(Long.class).single();
    }

    private long roleGrantedEvents(UUID user) {
        return jdbc.sql("select count(*) from platform_outbox where type = 'identity.role.granted' and aggregate_id = :u")
                .param("u", user.toString()).query(Long.class).single();
    }

    @Test
    void aRedeliveredApprovalIsAppliedOnce() throws Exception {
        String org = orgId();
        UUID owner = user("Malo");
        UUID eventId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-12T05:19:26Z");
        send("seller.org.approved", eventId, org, "APPROVED", owner, at);
        send("seller.org.approved", eventId, org, "APPROVED", owner, at);
        UUID after = UUID.randomUUID();
        send("seller.org.updated", after, org, "APPROVED", owner, at);

        assertThat(eventually(() -> inbox(after), n -> n == 1)).as("all three records consumed").isEqualTo(1);
        assertThat(status(org)).contains("APPROVED");
        assertThat(roles(org, owner)).containsExactly("seller.owner");
        assertThat(inbox(eventId)).isEqualTo(1);
        assertThat(roleGrantedEvents(owner)).as("the owner role is granted once").isEqualTo(1);
        assertThat(claims.forSubject(owner.toString()).orElseThrow().access().orgId()).contains(org);
    }

    @Test
    void aLateEventNeverRollsTheCopyBackAndALateApprovalStillAddsTheOwner() throws Exception {
        String org = orgId();
        UUID owner = user("Kemi");
        Instant approvedAt = Instant.parse("2026-10-12T05:19:26Z");
        // The suspension (newer) arrives before the approval it follows.
        send("seller.org.updated", UUID.randomUUID(), org, "SUSPENDED", owner, approvedAt.plusSeconds(300));
        UUID approval = UUID.randomUUID();
        send("seller.org.approved", approval, org, "APPROVED", owner, approvedAt);
        UUID stale = UUID.randomUUID();
        send("seller.org.updated", stale, org, "PENDING_REVIEW", owner, approvedAt.minusSeconds(60));

        assertThat(eventually(() -> inbox(stale), n -> n == 1)).isEqualTo(1);
        assertThat(status(org)).contains("SUSPENDED");
        assertThat(roles(org, owner)).containsExactly("seller.owner");
    }

    @Test
    void theReconcileRepairsAnApprovalIdentityNeverReceived() {
        String missing = orgId();
        String pending = orgId();
        UUID owner = user("Ada");
        UUID other = user("Bisi");
        Instant at = Instant.parse("2026-10-12T05:19:26Z");
        // Identity knows the second organisation only as PENDING, without its owner.
        jdbc.sql("insert into organization (id, kind, name, status, market, updated_at) values (:o, 'SELLER', 'Bisi', "
                + "'PENDING', 'US', :at)").params(Map.of("o", pending, "at", Timestamp.from(at.minusSeconds(600))))
                .update();
        FakeSeller.ORGS.add(new SellerOrgState(missing, "SELLER", "SELLER", "Ada Foods LLC", "Ada's Pantry", "US",
                "APPROVED", List.of(owner.toString()), owner.toString(), at));
        FakeSeller.ORGS.add(new SellerOrgState(pending, "SELLER", "SELLER", "Bisi LLC", "Bisi", "US", "APPROVED",
                List.of(other.toString()), other.toString(), at));

        OrgReconcileService.OrgReconcileSummary first = reconcile.reconcileAll();
        assertThat(first.repaired()).isGreaterThanOrEqualTo(2);
        assertThat(first.failed()).isZero();
        assertThat(status(missing)).contains("APPROVED");
        assertThat(status(pending)).contains("APPROVED");
        assertThat(roles(missing, owner)).containsExactly("seller.owner");
        assertThat(roles(pending, other)).containsExactly("seller.owner");
        // The owner's next token (claims are recomputed on refresh) carries the organisation.
        assertThat(claims.forSubject(owner.toString()).orElseThrow().access().orgId()).contains(missing);

        assertThat(reconcile.reconcileAll().repaired()).as("nothing left to repair").isZero();
    }
}
