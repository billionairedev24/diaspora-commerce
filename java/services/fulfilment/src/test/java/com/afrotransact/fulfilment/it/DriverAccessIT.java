package com.afrotransact.fulfilment.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import com.afrotransact.fulfilment.application.DriverAccessService;
import com.afrotransact.fulfilment.application.DriverCredential;
import com.afrotransact.fulfilment.application.DriverEvent;
import com.afrotransact.fulfilment.application.DriverSyncService;
import com.afrotransact.fulfilment.application.EventResult;
import com.afrotransact.fulfilment.application.IdentityAccessChange;
import com.afrotransact.fulfilment.application.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** S081: only delivery:operate gets in; revocation signs the app out without losing events captured before it. */
class DriverAccessIT extends FulfilmentIntegrationTest {

    private final DriverAccessService access;
    private final DriverSyncService syncService;

    @Autowired
    DriverAccessIT(DriverAccessService access, DriverSyncService syncService) {
        this.access = access;
        this.syncService = syncService;
    }

    @Test
    void usersWithoutDeliveryOperateSeeNoDriverAccessWithWhoCanGrantIt() {
        TestSupport.as(TestSupport.exDriver(id("kofi")));
        JsonNode p = problem(send("GET", "/v1/driver/me", null), 403, "fulfilment.no-driver-access");
        assertThat(p.path("signOut").asBoolean()).isFalse();
        assertThat(p.path("grantHint").asString()).contains("Access control").contains("Driver role");
        problem(send("POST", "/v1/driver/sync", Map.of("deviceId", "d", "events", List.of())), 403,
                "fulfilment.no-driver-access");
    }

    @Test
    void revocationSignsOutButKeepsEventsCapturedBeforeIt() {
        String driver = id("driver");
        TestSupport.as(TestSupport.driver(driver));
        ok(send("GET", "/v1/driver/me", null));

        Instant revokedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        assertThat(access.onIdentityChange(UUID.randomUUID(), new IdentityAccessChange("identity.permission.revoked",
                driver, null, "delivery:operate", "left the delivery team", revokedAt))).isTrue();
        assertThat(outboxTypes(driver)).contains("fulfilment.driver.access_revoked");

        // The old token (issued before the revocation) still carries delivery:operate: queued events still sync.
        DriverCredential oldToken = new DriverCredential("old-token", revokedAt.minusSeconds(600));
        SyncResult r = syncService.sync(id("device"), List.of(
                signedIn(1, revokedAt.minusSeconds(60)), signedIn(2, revokedAt.plusSeconds(60))), oldToken);
        assertThat(r.signOut()).isTrue();
        assertThat(r.revokedAt()).isEqualTo(revokedAt);
        assertThat(r.results()).extracting(EventResult::outcome).containsExactly("APPLIED", "REJECTED");
        assertThat(r.results().get(1).code()).isEqualTo("fulfilment.driver-access-revoked");

        // Their next token has no delivery:operate: sign-out, but earlier events are still accepted.
        TestSupport.as(TestSupport.exDriver(driver));
        JsonNode p = problem(send("GET", "/v1/driver/me", null), 403, "fulfilment.driver-access-revoked");
        assertThat(p.path("signOut").asBoolean()).isTrue();
        JsonNode late = sync(id("device"), List.of(event(1, "session.signed_in", null, null,
                revokedAt.minusSeconds(30))), "new-token");
        assertThat(late.path("signOut").asBoolean()).isTrue();
        assertThat(outcomes(late)).containsExactly("APPLIED");

        // Granted again: a token issued after the grant works.
        assertThat(access.onIdentityChange(UUID.randomUUID(), new IdentityAccessChange("identity.role.granted",
                driver, "platform.driver", null, null, revokedAt.plusSeconds(5)))).isTrue();
        TestSupport.as(TestSupport.driver(driver));
        assertThat(ok(send("GET", "/v1/driver/me", null)).path("access").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void dispatchSuspensionHoldsEvenWithAFreshToken() {
        String driver = id("driver");
        TestSupport.as(TestSupport.driver(driver));
        ok(send("GET", "/v1/driver/me", null));
        BlockingQueueHolder holder = new BlockingQueueHolder(openStream("/v1/driver/stream"));
        await(holder.lines(), "event:ready");

        TestSupport.as(TestSupport.dispatcher());
        JsonNode suspended = ok(send("POST", "/v1/dispatch/drivers/" + driver + "/suspend",
                Map.of("reason", "Safety review")));
        assertThat(suspended.path("status").asString()).isEqualTo("REVOKED");
        assertThat(String.join("\n", await(holder.lines(), "event:session.revoked"))).doesNotContain("error:");

        TestSupport.as(TestSupport.driver(driver));
        problem(send("GET", "/v1/driver/me", null), 403, "fulfilment.driver-access-revoked");
        TestSupport.as(TestSupport.dispatcher());
        ok(send("POST", "/v1/dispatch/drivers/" + driver + "/reinstate", null));
        TestSupport.as(TestSupport.driver(driver));
        ok(send("GET", "/v1/driver/me", null));
    }

    private static DriverEvent signedIn(long seq, Instant at) {
        return new DriverEvent(UUID.randomUUID().toString(), seq, "session.signed_in", null, null, at, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private record BlockingQueueHolder(BlockingQueue<String> lines) {
    }
}
