package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.identity.it.BrowserSession.AuthorizeResult;
import com.afrotransact.identity.it.BrowserSession.Tokens;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
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
 * S291 against real PostgreSQL: the identity admin console API. Clients are created, edited, rotated (old secret valid
 * for a grace period) and disabled, and the authorization server honours each change at once; sessions are listed and
 * revoked (a revoked refresh token stops working); the signing key rotates while tokens signed with the old key still
 * verify until it retires; the token inspector never stores the token; settings are validated, read at runtime and
 * audited; everything needs identity:admin and every change is in the access log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
@Testcontainers
class IdentityAdminIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String STOREFRONT = "customer-app";
    private static final String STOREFRONT_REDIRECT = "http://localhost:8081/auth/callback";
    private static final String STAFF_CONSOLE = "driver-app";
    private static final String STAFF_REDIRECT = "http://localhost:8082/auth/callback";
    private static final String ADMIN_EMAIL = "admin@afrotransact.com";
    private static final String ADMIN_PASSWORD = "LocalAdmin2026!";
    private static final String ADMIN_PHONE = "+17132490142";
    private static final String PASSWORD = "Plantain-Jollof-2026";
    private static final String REASON = "Integration test of the identity admin console";

    private static String adminRefreshToken;

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;
    private final List<ApplicationRunner> runners;

    @Autowired
    IdentityAdminIT(JdbcClient jdbc, List<ApplicationRunner> runners) {
        this.jdbc = jdbc;
        this.runners = runners;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private BrowserSession http() {
        return new BrowserSession(base());
    }

    private static String reason() {
        return "{\"reason\":\"" + REASON + "\"}";
    }

    @Test
    void onlyIdentityAdminCanUseTheConsoleApi() {
        BrowserSession s = http();
        String email = "plain." + UUID.randomUUID() + "@example.com";
        registerAndVerify(s, email);
        String customer = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD)).accessToken();

        for (String path : List.of("clients", "sessions", "signing-keys", "settings", "discovery")) {
            HttpResponse<String> r = s.api("GET", "/v1/admin/identity/" + path, customer, null);
            assertThat(r.statusCode()).as(path).isEqualTo(403);
        }
        assertThat(s.api("POST", "/v1/admin/identity/signing-keys/rotate", customer, reason()).statusCode())
                .isEqualTo(403);
        assertThat(s.api("POST", "/v1/admin/identity/token-inspector", customer, "{\"token\":\"a.b.c\"}").statusCode())
                .isEqualTo(403);
        assertThat(s.api("GET", "/v1/admin/identity/clients", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void clientsAreCreatedRotatedDisabledAndEditedWithTheAuthorizationServerFollowing() {
        BrowserSession s = http();
        String admin = adminToken();

        JsonNode list = BrowserSession.json(s.api("GET", "/v1/admin/identity/clients", admin, null).body());
        Set<String> ids = new HashSet<>();
        list.forEach(c -> ids.add(c.get("clientId").asString()));
        assertThat(ids).contains("customer-app", "driver-app", "gateway-bff");
        JsonNode bff = find(list, "gateway-bff");
        assertThat(bff.get("type").asString()).isEqualTo("confidential");
        assertThat(bff.get("platformClient").asBoolean()).isTrue();
        assertThat(bff.toString()).doesNotContain("secret\":\"");

        // create: needs a reason, shows the secret once
        String id = "it-svc-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"clientId":"%s","type":"confidential","name":"IT service","grantTypes":["client_credentials"],
                 "scopes":["it.read"],"accessTokenTtlMinutes":5,"refreshTokenTtlMinutes":60,"reason":"%s"}
                """.formatted(id, REASON);
        HttpResponse<String> noReason = s.api("POST", "/v1/admin/identity/clients", admin,
                body.replace(REASON, "short"));
        assertThat(noReason.statusCode()).isEqualTo(422);
        HttpResponse<String> created = s.api("POST", "/v1/admin/identity/clients", admin, body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String secret = BrowserSession.json(created.body()).get("secret").asString();
        assertThat(secret).hasSizeGreaterThan(30);
        assertThat(s.api("POST", "/v1/admin/identity/clients", admin, body).statusCode()).as("duplicate").isEqualTo(409);

        assertThat(s.clientCredentials(id, secret, "it.read").status()).isEqualTo(200);
        assertThat(s.clientCredentials(id, "wrong", "it.read").status()).isEqualTo(401);
        // the secret is stored only as a hash and is never read back
        assertThat(BrowserSession.json(s.api("GET", "/v1/admin/identity/clients/" + id, admin, null).body()).toString())
                .doesNotContain(secret);
        assertThat(jdbc.sql("select client_secret from oauth2_registered_client where client_id = :c").param("c", id)
                .query(String.class).single()).startsWith("{bcrypt}").doesNotContain(secret);

        // rotate: the old secret keeps working for the grace period, then stops
        HttpResponse<String> rotated = s.api("POST", "/v1/admin/identity/clients/" + id + "/rotate-secret", admin,
                reason());
        assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
        String fresh = BrowserSession.json(rotated.body()).get("secret").asString();
        assertThat(fresh).isNotEqualTo(secret);
        assertThat(s.clientCredentials(id, fresh, "it.read").status()).isEqualTo(200);
        assertThat(s.clientCredentials(id, secret, "it.read").status()).as("old secret in grace").isEqualTo(200);
        assertThat(BrowserSession.json(rotated.body()).get("client").get("previousSecretExpiresAt").isNull()).isFalse();
        jdbc.sql("update oauth_client_state set previous_secret_expires_at = now() - interval '1 minute' "
                + "where client_id = :c").param("c", id).update();
        assertThat(s.clientCredentials(id, secret, "it.read").status()).as("old secret after grace").isEqualTo(401);
        assertThat(s.clientCredentials(id, fresh, "it.read").status()).isEqualTo(200);

        // disable: refused at once; enable: works again; the platform's own client cannot be disabled
        assertThat(s.api("POST", "/v1/admin/identity/clients/" + id + "/disable", admin, reason()).statusCode())
                .isEqualTo(200);
        assertThat(s.clientCredentials(id, fresh, "it.read").status()).isEqualTo(401);
        assertThat(s.api("POST", "/v1/admin/identity/clients/" + id + "/enable", admin, reason()).statusCode())
                .isEqualTo(200);
        assertThat(s.clientCredentials(id, fresh, "it.read").status()).isEqualTo(200);
        assertThat(s.api("POST", "/v1/admin/identity/clients/gateway-bff/disable", admin, reason()).statusCode())
                .isEqualTo(422);

        // edit, and a restart's provisioning leaves the edit alone
        String edit = """
                {"name":"Renamed by an admin","grantTypes":["client_credentials"],"scopes":["it.read","it.write"],
                 "accessTokenTtlMinutes":3,"refreshTokenTtlMinutes":60,"reason":"%s"}
                """.formatted(REASON);
        HttpResponse<String> edited = s.api("PUT", "/v1/admin/identity/clients/" + id, admin, edit);
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(BrowserSession.json(edited.body()).get("accessTokenTtlMinutes").asInt()).isEqualTo(3);
        jdbc.sql("update oauth2_registered_client set client_name = 'Edited in the console' "
                + "where client_id = 'customer-app'").update();
        provisionAgain();
        assertThat(jdbc.sql("select client_name from oauth2_registered_client where client_id = 'customer-app'")
                .query(String.class).single()).isEqualTo("Edited in the console");

        // every change is in the access log with the reason and without the secret
        List<String> details = jdbc.sql("select detail from access_change_log where subject = :c order by id")
                .param("c", id).query(String.class).list();
        assertThat(details).hasSize(5).allMatch(d -> d.contains("reason=" + REASON))
                .noneMatch(d -> d.contains(secret) || d.contains(fresh));
        assertThat(jdbc.sql("select action from access_change_log where subject = :c order by id")
                .param("c", id).query(String.class).list()).containsExactly("oauth-client.created",
                "oauth-client.secret-rotated", "oauth-client.disabled", "oauth-client.enabled", "oauth-client.updated");
    }

    @Test
    void sessionsAreSearchedAndRevokedAndRevokedRefreshTokensStopWorking() {
        BrowserSession s = http();
        String email = "sessions." + UUID.randomUUID() + "@example.com";
        UUID userId = UUID.fromString(registerAndVerify(s, email));
        Tokens first = signInCustomer(email);
        Tokens second = signInCustomer(email);
        assertThat(first.refreshToken()).isNotNull();
        String admin = adminToken();

        JsonNode page = BrowserSession.json(s.api("GET", "/v1/admin/identity/sessions?q=" + email, admin, null).body());
        assertThat(page.get("total").asLong()).isEqualTo(2);
        JsonNode row = page.get("items").get(0);
        assertThat(row.get("clientId").asString()).isEqualTo(STOREFRONT);
        assertThat(row.get("userEmail").asString()).isEqualTo(email);
        assertThat(row.get("refreshable").asBoolean()).isTrue();
        assertThat(row.toString()).doesNotContain(first.refreshToken()).doesNotContain(first.accessToken());
        assertThat(BrowserSession.json(s.api("GET", "/v1/admin/identity/sessions?userId=" + userId + "&clientId="
                + STOREFRONT, admin, null).body()).get("total").asLong()).isEqualTo(2);

        // revoke one: its refresh token stops working at once, the other session is untouched
        String revoked = row.get("id").asString();
        assertThat(BrowserSession.json(s.api("GET", "/v1/admin/identity/sessions/" + revoked, admin, null).body())
                .get("clientId").asString()).isEqualTo(STOREFRONT);
        assertThat(s.api("GET", "/v1/admin/identity/sessions/no-such-session", admin, null).statusCode())
                .isEqualTo(404);
        assertThat(s.api("POST", "/v1/admin/identity/sessions/" + revoked + "/revoke", admin, "{\"reason\":\"x\"}")
                .statusCode()).isEqualTo(422);
        assertThat(s.api("POST", "/v1/admin/identity/sessions/" + revoked + "/revoke", admin, reason()).statusCode())
                .isEqualTo(204);
        int working = 0;
        for (Tokens t : List.of(first, second)) {
            if (s.refreshPublic(STOREFRONT, t.refreshToken()).status() == 200) {
                working++;
            }
        }
        assertThat(working).as("exactly one of the two refresh tokens still works").isEqualTo(1);

        // sign out everywhere
        Tokens third = signInCustomer(email);
        HttpResponse<String> all = s.api("POST", "/v1/admin/identity/users/" + userId + "/sessions/revoke", admin,
                reason());
        assertThat(all.statusCode()).as(all.body()).isEqualTo(200);
        assertThat(BrowserSession.json(all.body()).get("sessions").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(s.refreshPublic(STOREFRONT, third.refreshToken()).status()).isEqualTo(400);
        assertThat(BrowserSession.json(s.api("GET", "/v1/admin/identity/sessions?userId=" + userId, admin, null)
                .body()).get("total").asLong()).isZero();

        // all of a client's
        Tokens fourth = signInCustomer(email);
        HttpResponse<String> byClient = s.api("POST", "/v1/admin/identity/clients/" + STOREFRONT
                + "/sessions/revoke", admin, reason());
        assertThat(byClient.statusCode()).isEqualTo(200);
        assertThat(s.refreshPublic(STOREFRONT, fourth.refreshToken()).status()).isEqualTo(400);
        assertThat(jdbc.sql("select count(*) from access_change_log where action like 'session%' and detail like :r")
                .param("r", "%" + REASON + "%").query(Long.class).single()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void theSigningKeyRotatesTheOldKeyStaysPublishedUntilItRetiresAndTheInspectorValidates() throws Exception {
        BrowserSession s = http();
        String admin = adminToken();
        String oldKid = BrowserSession.json(new String(Base64.getUrlDecoder()
                .decode(admin.split("\\.")[0]))).get("kid").asString();

        JsonNode before = BrowserSession.json(s.api("GET", "/v1/admin/identity/signing-keys", admin, null).body());
        assertThat(before).hasSize(1);
        assertThat(before.get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(before.get(0).get("kid").asString()).isEqualTo(oldKid);
        assertThat(s.api("POST", "/v1/admin/identity/signing-keys/rotate", admin, "{\"reason\":\"\"}").statusCode())
                .isEqualTo(422);

        HttpResponse<String> rotation = s.api("POST", "/v1/admin/identity/signing-keys/rotate", admin, reason());
        assertThat(rotation.statusCode()).as(rotation.body()).isEqualTo(201);
        String newKid = BrowserSession.json(rotation.body()).get("kid").asString();
        assertThat(newKid).isNotEqualTo(oldKid);

        JsonNode after = BrowserSession.json(s.api("GET", "/v1/admin/identity/signing-keys", admin, null).body());
        assertThat(after).hasSize(2);
        assertThat(statusOf(after, oldKid)).isEqualTo("RETIRING");
        assertThat(statusOf(after, newKid)).isEqualTo("ACTIVE");
        assertThat(find(after, oldKid, "kid").get("retireAfter").isNull()).isFalse();

        // the table is re-read every few seconds: then new tokens carry the new kid and both keys are published
        String freshToken = await(() -> {
            String t = adminToken();
            String kid = BrowserSession.json(new String(Base64.getUrlDecoder()
                    .decode(t.split("\\.")[0]))).get("kid").asString();
            return newKid.equals(kid) ? t : null;
        });
        assertThat(publishedKids(s)).containsExactlyInAnyOrder(oldKid, newKid);

        // the token signed by the old key still validates while it is published; a private key is never shown
        JsonNode oldToken = BrowserSession.json(s.api("POST", "/v1/admin/identity/token-inspector", freshToken,
                "{\"token\":\"" + admin + "\"}").body());
        assertThat(oldToken.get("valid").asBoolean()).isTrue();
        assertThat(oldToken.get("keyId").asString()).isEqualTo(oldKid);
        assertThat(oldToken.get("signatureValid").asBoolean()).isTrue();
        assertThat(oldToken.get("issuerValid").asBoolean()).isTrue();
        assertThat(oldToken.get("claims").get("sub").asString()).isNotBlank();
        assertThat(oldToken.get("scopes").size()).isGreaterThan(0);
        HttpResponse<String> docs = s.api("GET", "/v1/admin/identity/discovery", freshToken, null);
        assertThat(docs.body()).contains("\"jwks_uri\"").contains("\"keys\"").doesNotContain("\"d\":");

        // tampered and opaque input is reported, not stored: the audit row names the subject and key, never the token
        String tampered = admin.substring(0, admin.length() - 4) + "AAAA";
        JsonNode bad = BrowserSession.json(s.api("POST", "/v1/admin/identity/token-inspector", freshToken,
                "{\"token\":\"" + tampered + "\"}").body());
        assertThat(bad.get("valid").asBoolean()).isFalse();
        assertThat(bad.get("signatureValid").asBoolean()).isFalse();
        assertThat(BrowserSession.json(s.api("POST", "/v1/admin/identity/token-inspector", freshToken,
                "{\"token\":\"opaque-refresh-token\"}").body()).get("wellFormed").asBoolean()).isFalse();
        assertThat(jdbc.sql("select count(*) from access_change_log where action = 'token.inspected' "
                + "and (detail like '%eyJ%' or detail like '%opaque-refresh-token%')").query(Long.class).single())
                .isZero();

        // once the overlap has ended the old key is retired and leaves the JWKS
        jdbc.sql("update signing_key set retire_after = now() - interval '1 second' where kid = :k")
                .param("k", oldKid).update();
        JsonNode retired = BrowserSession.json(s.api("GET", "/v1/admin/identity/signing-keys", freshToken, null)
                .body());
        assertThat(statusOf(retired, oldKid)).isEqualTo("RETIRED");
        await(() -> publishedKids(s).equals(Set.of(newKid)) ? "gone" : null);
        JsonNode gone = BrowserSession.json(s.api("POST", "/v1/admin/identity/token-inspector", freshToken,
                "{\"token\":\"" + admin + "\"}").body());
        assertThat(gone.get("signatureValid").asBoolean()).isFalse();
        assertThat(jdbc.sql("select private_key_sealed is null from signing_key where kid = :k").param("k", oldKid)
                .query(Boolean.class).single()).as("the private half of a rotated key is erased").isTrue();
        assertThat(jdbc.sql("select count(*) from access_change_log where action = 'signing-key.rotated'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void settingsAreValidatedStoredAuditedAndReadAtRuntime() {
        BrowserSession s = http();
        String admin = adminToken();

        JsonNode view = BrowserSession.json(s.api("GET", "/v1/admin/identity/settings", admin, null).body());
        assertThat(view.get("defaults").get("passwordMinLength").asInt()).isEqualTo(12);
        assertThat(view.get("overrides").get("passwordMinLength").isNull()).isTrue();

        HttpResponse<String> invalid = s.api("PUT", "/v1/admin/identity/settings", admin,
                "{\"overrides\":{\"passwordMinLength\":3,\"sessionIdleTimeoutMinutes\":2},\"reason\":\"" + REASON
                        + "\"}");
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("passwordMinLength").contains("sessionIdleTimeoutMinutes");
        assertThat(s.api("PUT", "/v1/admin/identity/settings", admin, "{\"overrides\":{},\"reason\":\"no\"}")
                .statusCode()).isEqualTo(422);

        try {
            HttpResponse<String> saved = s.api("PUT", "/v1/admin/identity/settings", admin, """
                    {"overrides":{"passwordMinLength":20,"sessionIdleTimeoutMinutes":15,"accessTokenTtlCapMinutes":1},
                     "reason":"%s"}""".formatted(REASON));
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            JsonNode effective = BrowserSession.json(saved.body()).get("effective");
            assertThat(effective.get("passwordMinLength").asInt()).isEqualTo(20);
            assertThat(effective.get("sessionIdleTimeoutMinutes").asInt()).isEqualTo(15);

            // read at runtime: a 13-character password is now refused at sign-up
            HttpResponse<String> shortPassword = s.api("POST", "/v1/accounts", null, """
                    {"firstName":"Adaeze","lastName":"Okafor","email":"pw.%s@example.com","phone":"(713) 249-0142",
                     "phoneRegion":"US","password":"Abcdefgh1jklm","confirmPassword":"Abcdefgh1jklm",
                     "termsAccepted":true,"smsConsent":false,"intent":"shop"}""".formatted(UUID.randomUUID()));
            assertThat(shortPassword.statusCode()).as(shortPassword.body()).isEqualTo(422);
            // and access tokens are capped to a minute, whatever the client allows
            String id = "it-cap-" + UUID.randomUUID().toString().substring(0, 8);
            String secret = BrowserSession.json(s.api("POST", "/v1/admin/identity/clients", admin, """
                    {"clientId":"%s","type":"confidential","name":"Cap","grantTypes":["client_credentials"],
                     "scopes":["it.read"],"accessTokenTtlMinutes":30,"reason":"%s"}""".formatted(id, REASON))
                    .body()).get("secret").asString();
            await(() -> {
                Tokens t = s.clientCredentials(id, secret, "it.read");
                long lifetime = t.accessClaims().get("exp").asLong() - t.accessClaims().get("iat").asLong();
                return lifetime <= 60 ? "capped" : null;
            });
            assertThat(jdbc.sql("select detail from access_change_log where action = 'auth-settings.updated' "
                    + "order by id desc limit 1").query(String.class).single())
                    .contains("reason=" + REASON, "before=passwordMin=12", "after=passwordMin=20");
        } finally {
            s.api("PUT", "/v1/admin/identity/settings", admin, "{\"overrides\":{},\"reason\":\"" + REASON + "\"}");
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static JsonNode find(JsonNode array, String id) {
        return find(array, id, "clientId");
    }

    private static JsonNode find(JsonNode array, String id, String key) {
        for (JsonNode n : array) {
            if (id.equals(n.get(key).asString())) {
                return n;
            }
        }
        throw new AssertionError("no " + key + " " + id + " in " + array);
    }

    private static String statusOf(JsonNode keys, String kid) {
        return find(keys, kid, "kid").get("status").asString();
    }

    private Set<String> publishedKids(BrowserSession s) {
        Set<String> kids = new HashSet<>();
        BrowserSession.json(s.api("GET", "/oauth2/jwks", null, null).body()).get("keys")
                .forEach(k -> kids.add(k.get("kid").asString()));
        return kids;
    }

    /** Polls until the supplier returns a value (the signing keys and settings are re-read every few seconds). */
    private static <T> T await(Supplier<T> supplier) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
        throw new AssertionError("condition not reached within 20 seconds");
    }

    private void provisionAgain() {
        ApplicationRunner provisioning = runners.stream()
                .filter(r -> r.getClass().getSimpleName().equals("StartupProvisioning")).findFirst().orElseThrow();
        try {
            provisioning.run(new DefaultApplicationArguments());
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private Tokens signInCustomer(String email) {
        BrowserSession s = http();
        Tokens t = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t;
    }

    /**
     * The bootstrap super admin signs in once (staff must pass a second factor, and a text-message code can be asked
     * for once a minute); later calls refresh the access token, which also signs it with the current signing key.
     */
    private synchronized String adminToken() {
        if (adminRefreshToken != null) {
            Tokens refreshed = http().refreshPublic(STAFF_CONSOLE, adminRefreshToken);
            assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
            adminRefreshToken = refreshed.refreshToken();
            return refreshed.accessToken();
        }
        BrowserSession admin = http();
        AuthorizeResult first = admin.signIn(STAFF_CONSOLE, STAFF_REDIRECT, ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(first.mfaRequired()).isTrue();
        admin.requestSmsCode();
        String code = admin.completeMfa("SMS", latestCode(ADMIN_PHONE), false);
        Tokens t = admin.exchangePublic(STAFF_CONSOLE, STAFF_REDIRECT, new AuthorizeResult(code, false,
                first.verifier()));
        assertThat(t.accessClaims().get("perms").toString()).contains("identity:admin");
        adminRefreshToken = t.refreshToken();
        return t.accessToken();
    }

    private String registerAndVerify(BrowserSession s, String email) {
        HttpResponse<String> created = s.api("POST", "/v1/accounts", null, """
                {"firstName":"Adaeze","lastName":"Okafor","email":"%s","phone":"(713) 249-0142","phoneRegion":"US",
                 "password":"%s","confirmPassword":"%s","termsAccepted":true,"smsConsent":false,"intent":"shop"}
                """.formatted(email, PASSWORD, PASSWORD));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        HttpResponse<String> verified = s.api("POST", "/v1/accounts/verification", null,
                "{\"email\":\"" + email + "\",\"code\":\"" + latestCode(email) + "\"}");
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(204);
        return BrowserSession.json(created.body()).get("userId").asString();
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
