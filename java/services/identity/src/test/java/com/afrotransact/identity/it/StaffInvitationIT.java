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
 * Staff invitations against real PostgreSQL: invite by name, email and role with a reason, the link in the queued
 * email event, a preview and acceptance with the invitee's own password, resend (the old link stops working), revoke,
 * expiry, the audit trail without a token or password in it, and the old temporary-password path being off. Also the
 * exact role filter on people and the role / permission filters on the access log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
@Testcontainers
class StaffInvitationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String STAFF_CONSOLE = "driver-app";
    private static final String STAFF_REDIRECT = "http://localhost:8082/auth/callback";
    private static final String ADMIN_EMAIL = "admin@afrotransact.com";
    private static final String ADMIN_PASSWORD = "LocalAdmin2026!";
    private static final String PASSWORD = "Plantain-Jollof-2026";
    private static final String REASON = "Joins customer support in the Lagos office";
    private static final String ROLE = "platform.customer_support";

    private static String adminRefreshToken;

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    StaffInvitationIT(JdbcClient jdbc) {
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

    private static String json(String email) {
        return """
                {"firstName":"Ada","lastName":"Obi","email":"%s","roleKey":"%s","reason":"%s"}"""
                .formatted(email, ROLE, REASON);
    }

    private static String email() {
        return "invitee." + UUID.randomUUID().toString().substring(0, 8) + "@afrotransact.com";
    }

    private HttpResponse<String> invite(BrowserSession s, String admin, String email) {
        return s.api("POST", "/v1/admin/staff-invitations", admin, json(email));
    }

    /** The token the email carries: read from the queued event, where the link is built (it is stored nowhere else). */
    private String tokenSent(String email) {
        String link = jdbc.sql("""
                select payload->>'acceptUrl' from platform_outbox
                where type = 'identity.staff-invitation.created' and payload->>'email' = :e
                order by occurred_at desc limit 1
                """).param("e", email).query(String.class).single();
        assertThat(link).contains("#token=");
        return link.substring(link.indexOf("#token=") + "#token=".length());
    }

    private static String body(String token, String password) {
        return "{\"token\":\"" + token + "\",\"password\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}";
    }

    @Test
    void inviteListPreviewAcceptWithTheInviteesOwnPasswordAndSpendTheToken() {
        BrowserSession s = http();
        String admin = adminToken();
        String email = email();

        HttpResponse<String> created = invite(s, admin, email);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode inv = BrowserSession.json(created.body());
        assertThat(inv.get("status").asString()).isEqualTo("PENDING");
        assertThat(inv.get("roleName").asString()).isEqualTo("Customer Support");
        assertThat(inv.get("invitedByName").asString()).isNotBlank();
        String token = tokenSent(email);
        assertThat(created.body()).as("the API never returns the token").doesNotContain(token);

        // only a hash is stored, and the token is in no table but the queued event
        assertThat(jdbc.sql("select token_hash from staff_invitation where lower(email) = :e").param("e", email)
                .query(String.class).single()).hasSize(64).isNotEqualTo(token);
        assertThat(jdbc.sql("select count(*) from staff_invitation where token_hash = :t or reason like :l")
                .param("t", token).param("l", "%" + token + "%").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select (expires_at - created_at) = interval '72 hours' from staff_invitation "
                + "where lower(email) = :e").param("e", email).query(Boolean.class).single()).isTrue();

        // the list shows names and emails
        JsonNode page = BrowserSession.json(s.api("GET", "/v1/admin/staff-invitations", admin, null).body());
        boolean listed = false;
        for (JsonNode row : page.get("items")) {
            listed |= email.equals(row.get("email").asString()) && "Ada".equals(row.get("firstName").asString())
                    && "Customer Support".equals(row.get("roleName").asString());
        }
        assertThat(listed).isTrue();

        // public preview: unknown token 404, the real one says who and what role, never the id
        assertThat(s.api("POST", "/v1/staff-invitations/preview", null, "{\"token\":\"nope\"}").statusCode()).isEqualTo(404);
        HttpResponse<String> preview = s.api("POST", "/v1/staff-invitations/preview", null, "{\"token\":\"" + token + "\"}");
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        assertThat(BrowserSession.json(preview.body()).get("email").asString()).isEqualTo(email);
        assertThat(preview.body()).doesNotContain("\"id\"");

        // the password policy applies; nothing is created for a weak one
        HttpResponse<String> weak = s.api("POST", "/v1/staff-invitations/accept", null, body(token, "short"));
        assertThat(weak.statusCode()).as(weak.body()).isEqualTo(422);
        assertThat(jdbc.sql("select count(*) from user_account where lower(email) = :e").param("e", email)
                .query(Long.class).single()).isZero();

        HttpResponse<String> accepted = s.api("POST", "/v1/staff-invitations/accept", null, body(token, PASSWORD));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        assertThat(accepted.body()).doesNotContain(PASSWORD);
        UUID userId = jdbc.sql("select id from user_account where lower(email) = :e").param("e", email)
                .query(UUID.class).single();
        assertThat(jdbc.sql("select role_key from user_role where user_id = :u").param("u", userId)
                .query(String.class).list()).containsExactly(ROLE);
        assertThat(jdbc.sql("select password_hash <> :p and enabled and email_verified from user_account where id = :u")
                .param("p", PASSWORD).param("u", userId).query(Boolean.class).single()).isTrue();

        // single use: the same link again is refused, and the invitation leaves the open list
        HttpResponse<String> again = s.api("POST", "/v1/staff-invitations/accept", null, body(token, PASSWORD));
        assertThat(again.statusCode()).isEqualTo(422);
        assertThat(again.body()).contains("identity.staff-invitation-used");
        assertThat(s.api("GET", "/v1/admin/staff-invitations", admin, null).body()).doesNotContain(email);

        // every step is in the audit trail, and neither the token nor a password is in it
        assertThat(jdbc.sql("select action from access_change_log where subject = :e or target_user = :u order by id")
                .param("e", email).param("u", userId).query(String.class).list())
                .contains("staff.invitation.created", "staff.invitation.accepted", "role.granted");
        assertThat(jdbc.sql("select count(*) from access_change_log where detail like :t or detail like :p or subject like :t")
                .param("t", "%" + token + "%").param("p", "%" + PASSWORD + "%").query(Long.class).single()).isZero();

        // the people search filters by role exactly; the log by role and permission
        JsonNode holders = BrowserSession.json(s.api("GET", "/v1/admin/users?role=" + ROLE + "&size=100", admin, null).body());
        boolean holds = false;
        for (JsonNode u : holders.get("items")) {
            holds |= email.equals(u.get("email").asString());
            assertThat(u.get("roles").toString()).contains(ROLE);
        }
        assertThat(holds).isTrue();
        assertThat(holders.get("total").asLong()).isEqualTo(holders.get("items").size());
        JsonNode log = BrowserSession.json(s.api("GET", "/v1/admin/access-log?role=" + ROLE + "&size=100", admin, null).body());
        assertThat(log.get("total").asLong()).isPositive();
        for (JsonNode e : log.get("items")) {
            assertThat(e.get("targetRole").asString()).isEqualTo(ROLE);
        }
        JsonNode none = BrowserSession.json(s.api("GET", "/v1/admin/access-log?permission=no:such", admin, null).body());
        assertThat(none.get("total").asLong()).isZero();
    }

    @Test
    void resendMakesTheOldLinkStopWorkingAndRevokeAndExpiryEndTheNewOne() {
        BrowserSession s = http();
        String admin = adminToken();
        String email = email();
        String id = BrowserSession.json(invite(s, admin, email).body()).get("id").asString();
        String first = tokenSent(email);

        HttpResponse<String> resent = s.api("POST", "/v1/admin/staff-invitations/" + id + "/resend", admin, null);
        assertThat(resent.statusCode()).as(resent.body()).isEqualTo(200);
        assertThat(BrowserSession.json(resent.body()).get("sendCount").asInt()).isEqualTo(2);
        String second = tokenSent(email);
        assertThat(second).isNotEqualTo(first);
        assertThat(s.api("POST", "/v1/staff-invitations/preview", null, "{\"token\":\"" + first + "\"}").statusCode()).isEqualTo(404);
        assertThat(s.api("POST", "/v1/staff-invitations/preview", null, "{\"token\":\"" + second + "\"}").statusCode()).isEqualTo(200);

        // expiry: past its time the link says so and cannot be used, and the list still offers a resend
        jdbc.sql("update staff_invitation set expires_at = now() - interval '1 minute' where id = cast(:i as uuid)")
                .param("i", id).update();
        assertThat(BrowserSession.json(s.api("POST", "/v1/staff-invitations/preview", null, "{\"token\":\"" + second + "\"}").body())
                .get("status").asString()).isEqualTo("EXPIRED");
        HttpResponse<String> late = s.api("POST", "/v1/staff-invitations/accept", null, body(second, PASSWORD));
        assertThat(late.statusCode()).isEqualTo(422);
        assertThat(late.body()).contains("identity.staff-invitation-expired");
        assertThat(s.api("GET", "/v1/admin/staff-invitations", admin, null).body()).contains("EXPIRED");
        String third = BrowserSession.json(s.api("POST", "/v1/admin/staff-invitations/" + id + "/resend", admin, null).body())
                .get("id").asString();
        assertThat(third).isEqualTo(id);
        String latest = tokenSent(email);

        // revoke needs a reason and ends the link at once
        assertThat(s.api("POST", "/v1/admin/staff-invitations/" + id + "/revoke", admin, "{\"reason\":\"x\"}").statusCode()).isEqualTo(400);
        assertThat(s.api("POST", "/v1/admin/staff-invitations/" + id + "/revoke", admin, "{\"reason\":\"Offer withdrawn\"}").statusCode())
                .isEqualTo(204);
        HttpResponse<String> gone = s.api("POST", "/v1/staff-invitations/accept", null, body(latest, PASSWORD));
        assertThat(gone.statusCode()).isEqualTo(422);
        assertThat(gone.body()).contains("identity.staff-invitation-revoked");
        assertThat(jdbc.sql("select count(*) from user_account where lower(email) = :e").param("e", email)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select action from access_change_log where subject = :e order by id").param("e", email)
                .query(String.class).list()).contains("staff.invitation.created", "staff.invitation.resent",
                "staff.invitation.revoked");
    }

    @Test
    void onlyAccessAdminCanInviteAccessReadCanListAndTheTemporaryPasswordPathIsOff() {
        BrowserSession s = http();
        String admin = adminToken();
        assertThat(s.api("GET", "/v1/admin/staff-invitations", null, null).statusCode()).isEqualTo(401);
        assertThat(s.api("POST", "/v1/staff-invitations/preview", null, "{}").statusCode()).isEqualTo(400);

        // a foreign email domain is refused for staff
        HttpResponse<String> foreign = invite(s, admin, "someone@gmail.com");
        assertThat(foreign.statusCode()).isEqualTo(422);

        // the old temporary-password account creation answers 409 unless switched on
        HttpResponse<String> temp = s.api("POST", "/v1/admin/users", admin, """
                {"firstName":"Old","lastName":"Path","email":"%s","phone":"+17132490142","phoneRegion":"US",
                 "password":"%s","roles":["%s"]}""".formatted(email(), PASSWORD, ROLE));
        assertThat(temp.statusCode()).as(temp.body()).isEqualTo(409);
        assertThat(temp.body()).contains("identity.temp-password-disabled");
    }
}
