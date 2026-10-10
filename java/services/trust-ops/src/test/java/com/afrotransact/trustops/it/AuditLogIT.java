package com.afrotransact.trustops.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.trustops.application.AuditLogService;
import com.afrotransact.trustops.application.IncomingEvent;
import com.afrotransact.trustops.domain.AuditEntry;
import com.afrotransact.trustops.domain.ChainVerification;
import com.afrotransact.trustops.port.AuditQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/** S119: ingestion, filters, CSV export, chain verification and the database-level append-only guarantee. */
class AuditLogIT extends TrustOpsIntegrationTest {

    private final AuditLogService audit;

    @Autowired
    AuditLogIT(AuditLogService audit) {
        this.audit = audit;
    }

    private static IncomingEvent roleGranted(String admin, String user, String role) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user);
        payload.put("roleKey", role);
        payload.put("orgId", null);
        payload.put("actorId", admin);
        payload.put("reason", "Joined trust team");
        payload.put("at", "2026-10-09T14:50:00Z");
        String json = JSON.writeValueAsString(payload);
        return new IncomingEvent(UUID.randomUUID(), "at.identity.events", "identity.role.granted", user, json,
                payload, Instant.parse("2026-10-09T14:50:00Z"));
    }

    @Test
    void ingestsAuditWorthyEventsOnceAndKeepsThePayloadVerbatim() {
        String user = UUID.randomUUID().toString();
        IncomingEvent e = roleGranted("admin-ife", user, "platform.trust_safety");
        assertThat(audit.ingest(e)).isTrue();
        assertThat(audit.ingest(e)).as("redelivery").isFalse();

        AuditEntry entry = audit.search(new AuditQuery(null, "USER", user, null, null, null, null), 0, 10).items()
                .getFirst();
        assertThat(entry.facts().actorId()).isEqualTo("admin-ife");
        assertThat(entry.facts().summary()).isEqualTo("Granted role platform.trust_safety — Joined trust team");
        assertThat(entry.draft().payload()).isEqualTo(e.payloadJson());
        assertThat(entry.draft().sourceEventId()).isEqualTo(e.eventId());
        assertThat(entry.draft().occurredAt()).isEqualTo(Instant.parse("2026-10-09T14:50:00Z"));

        // Not configured / not privileged: ignored.
        assertThat(audit.ingest(new IncomingEvent(UUID.randomUUID(), "at.identity.events",
                "identity.user.registered", "u", "{}", Map.of(), null))).isFalse();
        assertThat(audit.ingest(new IncomingEvent(UUID.randomUUID(), "at.order.events",
                "orders.shipment.status_changed", "o", "{}", Map.of("actorType", "CARRIER", "to", "delivered"),
                null))).isFalse();
    }

    @Test
    void filtersByActorEntityActionAndDate() {
        String admin = "admin-" + UUID.randomUUID();
        String u1 = UUID.randomUUID().toString();
        String u2 = UUID.randomUUID().toString();
        audit.ingest(roleGranted(admin, u1, "platform.finance"));
        audit.ingest(roleGranted(admin, u2, "platform.auditor"));

        assertThat(audit.search(new AuditQuery(admin, null, null, null, null, null, null), 0, 10).total())
                .isEqualTo(2);
        assertThat(audit.search(new AuditQuery(admin, "USER", u2, "identity.role.granted", null, null, null), 0, 10)
                .items()).singleElement().satisfies(x -> assertThat(x.facts().entityId()).isEqualTo(u2));
        assertThat(audit.search(new AuditQuery(admin, null, null, null, null,
                Instant.parse("2026-10-10T00:00:00Z"), null), 0, 10).total()).isZero();
        assertThat(audit.search(new AuditQuery(admin, null, null, null, null,
                Instant.parse("2026-10-09T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z")), 0, 10).total())
                .isEqualTo(2);

        TestSupport.as(TestSupport.marketing());
        assertThatThrownBy(() -> audit.search(new AuditQuery(null, null, null, null, null, null, null), 0, 10))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void csvExportOverHttp() {
        String admin = "admin-csv-" + UUID.randomUUID();
        audit.ingest(roleGranted(admin, UUID.randomUUID().toString(), "platform.marketing"));
        HttpResponse<String> csv = send("GET", "/v1/audit/export.csv?actorId=" + admin, null);

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        String[] lines = csv.body().split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("seq,occurred_at,recorded_at,actor_id");
        assertThat(lines[1]).contains(admin, "identity.role.granted", "Granted role platform.marketing");

        JsonNode page = ok(send("GET", "/v1/audit/entries?actorId=" + admin, null));
        assertThat(page.path("items").get(0).path("hash").asString()).hasSize(64);
    }

    @Test
    void chainVerifiesAndConcurrentAppendsStayLinked() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String user = UUID.randomUUID().toString();
                jobs.add(() -> audit.ingest(roleGranted("admin-parallel", user, "platform.dispatcher")));
            }
            for (Future<Boolean> f : pool.invokeAll(jobs)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdown();
        }
        ChainVerification v = audit.verify(null, null);
        assertThat(v.valid()).as(String.valueOf(v)).isTrue();
        assertThat(v.checked()).isGreaterThanOrEqualTo(24);
        assertThat(jdbc.sql("select count(*) from audit_entry").query(Long.class).single()).isEqualTo(v.lastSeq());

        JsonNode http = ok(send("GET", "/v1/audit/verify", null));
        assertThat(http.path("valid").asBoolean()).isTrue();
    }

    @Test
    void updateDeleteAndTruncateAreRejectedByTheDatabase() {
        audit.ingest(roleGranted("admin-immutable", UUID.randomUUID().toString(), "platform.finance"));
        long seq = jdbc.sql("select max(seq) from audit_entry").query(Long.class).single();

        assertThatThrownBy(() -> jdbc.sql("update audit_entry set actor_id = 'mallory' where seq = :seq")
                .param("seq", seq).update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from audit_entry where seq = :seq").param("seq", seq).update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("truncate audit_entry").update())
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("append-only");
        assertThat(jdbc.sql("select actor_id from audit_entry where seq = :seq").param("seq", seq)
                .query(String.class).single()).isEqualTo("admin-immutable");
    }

    @Test
    void verifyDetectsTamperingThatBypassesTheTrigger() {
        audit.ingest(roleGranted("admin-tamper", UUID.randomUUID().toString(), "platform.finance"));
        long seq = jdbc.sql("select max(seq) from audit_entry where actor_id = 'admin-tamper'").query(Long.class)
                .single();
        // A superuser could disable the trigger; the hash chain still exposes the edit.
        jdbc.sql("alter table audit_entry disable trigger audit_entry_no_update_delete").update();
        try {
            jdbc.sql("update audit_entry set actor_id = 'mallory' where seq = :seq").param("seq", seq).update();
            ChainVerification v = audit.verify(null, null);
            assertThat(v.valid()).isFalse();
            assertThat(v.brokenAtSeq()).isEqualTo(seq);
            assertThat(v.problem()).contains("Content");
        } finally {
            jdbc.sql("update audit_entry set actor_id = 'admin-tamper' where seq = :seq").param("seq", seq).update();
            jdbc.sql("alter table audit_entry enable trigger audit_entry_no_update_delete").update();
        }
        assertThat(audit.verify(null, null).valid()).isTrue();
    }
}
