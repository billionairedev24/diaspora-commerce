package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import com.afrotransact.identity.it.BrowserSession.AuthorizeResult;
import com.afrotransact.identity.it.BrowserSession.Tokens;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The module access matrix against real PostgreSQL (Settings > Roles and permissions > Module access): read, change one
 * cell with a reason that lands in the access change log, and the lock-out guard that keeps Super Admin on Users & roles
 * and Access control.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
@Testcontainers
class ModuleAccessIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String STAFF_CONSOLE = "driver-app";
    private static final String STAFF_REDIRECT = "http://localhost:8082/auth/callback";
    private static final String ADMIN_EMAIL = "admin@afrotransact.com";
    private static final String ADMIN_PASSWORD = "LocalAdmin2026!";

    private static String adminRefreshToken;

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    ModuleAccessIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private BrowserSession http() {
        return new BrowserSession("http://localhost:" + port);
    }

    private synchronized String adminToken() {
        if (adminRefreshToken != null) {
            Tokens refreshed = http().refreshPublic(STAFF_CONSOLE, adminRefreshToken);
            assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
            adminRefreshToken = refreshed.refreshToken();
            return refreshed.accessToken();
        }
        BrowserSession admin = http();
        AuthorizeResult staff = admin.signInStaff(STAFF_CONSOLE, STAFF_REDIRECT, ADMIN_EMAIL, ADMIN_PASSWORD);
        Tokens t = admin.exchangePublic(STAFF_CONSOLE, STAFF_REDIRECT, staff);
        adminRefreshToken = t.refreshToken();
        return t.accessToken();
    }

    private String level(JsonNode matrix, String module, String role) {
        for (JsonNode row : matrix.get("modules")) {
            if (module.equals(row.get("key").asString())) {
                return row.get("levels").get(role).asString();
            }
        }
        throw new AssertionError("no module " + module);
    }

    @Test
    void aCellChangesWithAReasonInTheLogAndSuperAdminKeepsUsersAndRolesAndAccessControl() {
        BrowserSession s = http();
        String admin = adminToken();
        JsonNode before = BrowserSession.json(s.api("GET", "/v1/admin/module-access?portal=platform", admin, null).body());
        assertThat(before.get("modules").size()).isGreaterThan(20);
        assertThat(level(before, "rbac", "platform.super_admin")).isEqualTo("full");
        String original = level(before, "coupons", "platform.finance");
        String target = "edit".equals(original) ? "view" : "edit";

        String why = "Quarter close needs " + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> changed = s.api("PUT", "/v1/admin/module-access/platform/coupons/platform.finance", admin,
                "{\"level\":\"" + target + "\",\"reason\":\"" + why + "\"}");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(BrowserSession.json(changed.body()).get("level").asString()).isEqualTo(target);
        assertThat(level(BrowserSession.json(s.api("GET", "/v1/admin/module-access?portal=platform", admin, null).body()),
                "coupons", "platform.finance")).isEqualTo(target);
        JsonNode log = BrowserSession.json(s.api("GET", "/v1/admin/access-log?role=platform.finance&size=5", admin, null).body());
        assertThat(log.get("items").get(0).get("action").asString()).isEqualTo("module.access.changed");
        assertThat(log.get("items").get(0).get("detail").asString()).isEqualTo(original + " → " + target + ": " + why);
        s.api("PUT", "/v1/admin/module-access/platform/coupons/platform.finance", admin,
                "{\"level\":\"" + original + "\",\"reason\":\"restore\"}");

        // the lock-out guard: Super Admin keeps Edit on Users & roles and on Access control
        for (String module : new String[] {"rbac", "access"}) {
            HttpResponse<String> locked = s.api("PUT", "/v1/admin/module-access/platform/" + module + "/platform.super_admin",
                    admin, "{\"level\":\"view\",\"reason\":\"tidy up\"}");
            assertThat(locked.statusCode()).as(locked.body()).isEqualTo(409);
            assertThat(locked.body()).contains("identity.access-lockout");
        }
        assertThat(level(BrowserSession.json(s.api("GET", "/v1/admin/module-access?portal=platform", admin, null).body()),
                "rbac", "platform.super_admin")).isEqualTo("full");

        // unknown module, role of another portal, a level that does not exist
        assertThat(s.api("PUT", "/v1/admin/module-access/platform/nope/platform.finance", admin, "{\"level\":\"view\"}")
                .statusCode()).isEqualTo(404);
        assertThat(s.api("PUT", "/v1/admin/module-access/seller/sTeam/platform.finance", admin, "{\"level\":\"view\"}")
                .statusCode()).isEqualTo(422);
        assertThat(s.api("PUT", "/v1/admin/module-access/platform/coupons/platform.finance", admin, "{\"level\":\"god\"}")
                .statusCode()).isGreaterThanOrEqualTo(400);
        // anonymous callers are refused
        assertThat(s.api("PUT", "/v1/admin/module-access/platform/coupons/platform.finance", null, "{\"level\":\"view\"}")
                .statusCode()).isEqualTo(401);
    }
}
