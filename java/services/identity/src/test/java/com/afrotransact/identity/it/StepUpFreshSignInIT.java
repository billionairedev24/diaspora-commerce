package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Base64;
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
 * S291 step-up with the default five-minute window: a staff member who has just signed in does not have to re-enter
 * the password for a dangerous action (the access token carries {@code auth_time}), and the action is audited.
 * {@link IdentityAdminIT} covers the other way, with every sign-in treated as stale.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
@Testcontainers
class StepUpFreshSignInIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String STAFF_CONSOLE = "driver-app";
    private static final String STAFF_REDIRECT = "http://localhost:8082/auth/callback";
    private static final String ADMIN_EMAIL = "admin@afrotransact.com";
    private static final String ADMIN_PASSWORD = "LocalAdmin2026!";
    private static final String ADMIN_PHONE = "+17132490142";
    private static final String REASON = "Integration test of the step-up fresh sign-in";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    StepUpFreshSignInIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void aSignInFromTheLastFiveMinutesNeedsNoPasswordForADangerousAction() {
        BrowserSession s = new BrowserSession("http://localhost:" + port);
        BrowserSession admin = new BrowserSession("http://localhost:" + port);
        AuthorizeResult first = admin.signIn(STAFF_CONSOLE, STAFF_REDIRECT, ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(first.mfaRequired()).isTrue();
        admin.requestSmsCode();
        String code = admin.completeMfa("SMS", latestCode(ADMIN_PHONE), false);
        Tokens t = admin.exchangePublic(STAFF_CONSOLE, STAFF_REDIRECT, new AuthorizeResult(code, false,
                first.verifier()));
        String token = t.accessToken();
        long authTime = BrowserSession.json(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1])))
                .get("auth_time").asLong();
        assertThat(System.currentTimeMillis() / 1000 - authTime).as("auth_time is the sign-in").isBetween(0L, 120L);

        // no proof, no password: the fresh sign-in is the confirmation
        String id = "it-fresh-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = s.api("POST", "/v1/admin/identity/clients", token, """
                {"clientId":"%s","type":"confidential","name":"Fresh","grantTypes":["client_credentials"],
                 "scopes":["it.read"],"reason":"%s"}""".formatted(id, REASON));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        HttpResponse<String> disabled = s.api("POST", "/v1/admin/identity/clients/" + id + "/disable", token,
                "{\"reason\":\"" + REASON + "\"}");
        assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(200);
        assertThat(jdbc.sql("select detail from access_change_log where action = 'oauth-client.disabled' "
                + "and subject = :c").param("c", id).query(String.class).single()).contains("reason=" + REASON);

        // the signing-key rotation too: the new key is the one signing from now on
        JsonNode rotated = BrowserSession.json(s.api("POST", "/v1/admin/identity/signing-keys/rotate", token,
                "{\"reason\":\"" + REASON + "\"}").body());
        assertThat(rotated.get("status").asString()).isEqualTo("ACTIVE");
    }

    private String latestCode(String destination) {
        return jdbc.sql("""
                select payload->>'code' from platform_outbox
                where type = 'identity.verification.requested' and aggregate_type = 'identity'
                  and coalesce(payload->'recipient'->>'email', payload->'recipient'->>'phone') = :d
                  and payload->>'channel' = case when :d like '+%' then 'sms' else 'email' end
                order by occurred_at desc limit 1
                """).param("d", destination).query(String.class).single();
    }
}
