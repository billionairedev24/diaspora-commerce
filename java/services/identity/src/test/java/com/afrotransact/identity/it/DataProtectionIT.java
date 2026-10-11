package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.adapter.out.crypto.TestSecretCiphers;
import com.afrotransact.identity.domain.DefaultTotpAuthenticator;
import com.afrotransact.identity.it.BrowserSession.AuthorizeResult;
import com.afrotransact.identity.it.BrowserSession.Tokens;
import com.afrotransact.identity.port.EncryptedSecretRepository;
import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * S202 against real PostgreSQL: the database never holds a TOTP secret in plain text — not after enrolment, and not
 * for a row written by the previous release (the schema is migrated to V12 and a plaintext row inserted before the
 * application starts; start-up then applies V13 and seals it). A super admin rotates secrets from a retired key to the
 * current one while sign-ins keep working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox",
    "at.identity.data-protection.key-id=" + DataProtectionIT.CURRENT_KEY_ID,
    "at.identity.data-protection.key=" + DataProtectionIT.CURRENT_KEY,
    "at.identity.data-protection.previous-keys=" + DataProtectionIT.OLD_KEY_ID + ":" + DataProtectionIT.OLD_KEY})
@ActiveProfiles("local")
@Testcontainers
class DataProtectionIT {

    static final String CURRENT_KEY_ID = "it-current";
    static final String CURRENT_KEY = "mB3FPeNA2n3gpPjBDhziub681AZitNXX4hoUn2t/IQ0=";
    static final String OLD_KEY_ID = "it-old";
    static final String OLD_KEY = "diGeNCRjM04dmY4SGJwN5VKqVcQ/I0qD642Ca2Dc7kg=";
    private static final String LOST_KEY = "Wq0Hr8kq3mJQ1oXz0bYw8l2v9rS5u4t3p2o1n0m9l8k=";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    /** A public PKCE client with refresh tokens (the customer app); web apps sign in through gateway-bff. */
    private static final String STOREFRONT = "customer-app";
    private static final String STOREFRONT_REDIRECT = "http://localhost:8081/auth/callback";
    /** Public client without the phone scope (the driver app), standing in for a staff-facing app. */
    private static final String STAFF_CONSOLE = "driver-app";
    private static final String STAFF_REDIRECT = "http://localhost:8082/auth/callback";
    private static final String ADMIN_EMAIL = "admin@afrotransact.com";
    private static final String ADMIN_PASSWORD = "LocalAdmin2026!";
    private static final String ADMIN_PHONE = "+17132490142";
    private static final String PASSWORD = "Plantain-Jollof-2026";

    private static final DefaultTotpAuthenticator TOTP = new DefaultTotpAuthenticator();
    private static final UUID LEGACY_USER = UUID.randomUUID();
    private static final String LEGACY_EMAIL = "legacy.totp." + LEGACY_USER + "@example.com";
    private static final String LEGACY_SECRET = TOTP.newSecret();

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    DataProtectionIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The database as the previous release left it: schema V12, TOTP secret in the plaintext column. */
    @BeforeAll
    static void databaseFromThePreviousRelease() throws SQLException {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/platform", "classpath:db/migration").target("12").load().migrate();
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            try (PreparedStatement user = c.prepareStatement("""
                    insert into user_account (id, first_name, last_name, email, email_verified, password_hash,
                        terms_accepted_at, created_at, updated_at)
                    values (?, 'Legacy', 'Enrolment', ?, true, ?, ?, ?, ?)
                    """)) {
                user.setObject(1, LEGACY_USER);
                user.setString(2, LEGACY_EMAIL);
                user.setString(3, PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
                user.setTimestamp(4, now);
                user.setTimestamp(5, now);
                user.setTimestamp(6, now);
                user.executeUpdate();
            }
            try (PreparedStatement role = c.prepareStatement(
                    "insert into user_role (user_id, role_key, granted_by) values (?, 'account.customer', 'it')")) {
                role.setObject(1, LEGACY_USER);
                role.executeUpdate();
            }
            try (PreparedStatement totp = c.prepareStatement("""
                    insert into mfa_totp (user_id, secret, created_at, confirmed_at, last_used_step)
                    values (?, ?, ?, ?, 0)
                    """)) {
                totp.setObject(1, LEGACY_USER);
                totp.setString(2, LEGACY_SECRET);
                totp.setTimestamp(3, now);
                totp.setTimestamp(4, now);
                totp.executeUpdate();
            }
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void plaintextSecretFromThePreviousReleaseIsSealedAtStartUp() {
        StoredTotp row = stored(LEGACY_USER);
        assertThat(row.plaintext()).as("plaintext column emptied").isNull();
        assertThat(row.keyId()).isEqualTo(CURRENT_KEY_ID);
        assertNotInDatabase(LEGACY_SECRET);
        SecretCipher current = TestSecretCiphers.local(CURRENT_KEY_ID, CURRENT_KEY, "");
        assertThat(current.open(row.ciphertext(), TestSecretCiphers.totpContext(LEGACY_USER)))
                .isEqualTo(LEGACY_SECRET);

        // The user's authenticator app keeps working.
        signInWithTotp(LEGACY_EMAIL, LEGACY_SECRET, Instant.now().getEpochSecond() / 30);
    }

    @Test
    void totpEnrolmentNeverWritesThePlaintextSecret() {
        BrowserSession s = new BrowserSession(base());
        String email = "totp.sealed." + UUID.randomUUID() + "@example.com";
        UUID userId = UUID.fromString(registerAndVerify(s, email));
        String token = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD)).accessToken();

        JsonNode setup = BrowserSession.json(s.api("POST", "/v1/me/mfa/totp", token, null).body());
        String secret = setup.get("secret").asString();
        assertThat(secret).hasSizeGreaterThanOrEqualTo(32);
        assertThat(stored(userId).keyId()).as("pending enrolment").isEqualTo(CURRENT_KEY_ID);
        assertNotInDatabase(secret);

        long step = Instant.now().getEpochSecond() / 30;
        HttpResponse<String> confirmed = s.api("POST", "/v1/me/mfa/totp/confirm", token,
                "{\"code\":\"" + TOTP.codeAt(secret, step) + "\"}");
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        StoredTotp row = stored(userId);
        assertThat(row.plaintext()).isNull();
        assertThat(row.keyId()).isEqualTo(CURRENT_KEY_ID);
        assertNotInDatabase(secret);
        assertThat(jdbc.sql("select count(*) from mfa_totp where secret is not null").query(Long.class).single())
                .as("no plaintext TOTP secret anywhere").isZero();

        // The confirm used this step (replay-protected); the next one is inside the ±1 step window.
        signInWithTotp(email, secret, step + 1);
        assertNotInDatabase(secret);
    }

    @Test
    void superAdminRotatesSecretsToTheCurrentKeyWhileSignInsKeepWorking() {
        BrowserSession s = new BrowserSession(base());
        String email = "totp.rotated." + UUID.randomUUID() + "@example.com";
        UUID rotated = UUID.fromString(registerAndVerify(s, email));
        String rotatedSecret = TOTP.newSecret();
        insertSealed(rotated, rotatedSecret, TestSecretCiphers.local(OLD_KEY_ID, OLD_KEY, ""));
        UUID lost = UUID.fromString(registerAndVerify(s, "totp.lost." + UUID.randomUUID() + "@example.com"));
        insertSealed(lost, TOTP.newSecret(), TestSecretCiphers.local("it-lost", LOST_KEY, ""));

        // Before rotation the retired key still opens the secret (IDENTITY_DATA_KEYS_PREVIOUS).
        long step = Instant.now().getEpochSecond() / 30;
        signInWithTotp(email, rotatedSecret, step);
        assertThat(stored(rotated).keyId()).as("a sign-in re-seals its row under the current key")
                .isEqualTo(CURRENT_KEY_ID);
        insertSealed(rotated, rotatedSecret, TestSecretCiphers.local(OLD_KEY_ID, OLD_KEY, ""));

        String customerEmail = "not.admin." + UUID.randomUUID() + "@example.com";
        registerAndVerify(s, customerEmail);
        String customer = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, customerEmail, PASSWORD)).accessToken();
        assertThat(s.api("GET", "/v1/admin/data-protection", customer, null).statusCode()).isEqualTo(403);
        HttpResponse<String> denied = s.api("POST", "/v1/admin/data-protection/rotate", customer, null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(BrowserSession.json(denied.body()).get("code").asString())
                .isEqualTo("identity.super-admin-required");

        String admin = adminToken();
        JsonNode status = BrowserSession.json(s.api("GET", "/v1/admin/data-protection", admin, null).body());
        assertThat(status.get("currentKeyId").asString()).isEqualTo(CURRENT_KEY_ID);
        assertThat(status.get("secretsByKey").get(OLD_KEY_ID).asLong()).isEqualTo(1);
        assertThat(status.get("secretsByKey").get("it-lost").asLong()).isEqualTo(1);

        HttpResponse<String> rotation = s.api("POST", "/v1/admin/data-protection/rotate", admin, null);
        assertThat(rotation.statusCode()).as(rotation.body()).isEqualTo(200);
        JsonNode report = BrowserSession.json(rotation.body());
        assertThat(report.get("keyId").asString()).isEqualTo(CURRENT_KEY_ID);
        assertThat(report.get("resealed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(report.get("failed").asInt()).as("a key missing from the ring is reported, not fatal")
                .isEqualTo(1);
        assertThat(report.get("remaining").has(OLD_KEY_ID)).isFalse();
        assertThat(report.get("remaining").has(EncryptedSecretRepository.PLAINTEXT)).isFalse();
        assertThat(report.get("remaining").get("it-lost").asLong()).isEqualTo(1);

        StoredTotp row = stored(rotated);
        assertThat(row.keyId()).isEqualTo(CURRENT_KEY_ID);
        assertThat(TestSecretCiphers.local(CURRENT_KEY_ID, CURRENT_KEY, "").open(row.ciphertext(),
                TestSecretCiphers.totpContext(rotated))).isEqualTo(rotatedSecret);
        assertNotInDatabase(rotatedSecret);
        signInWithTotp(email, rotatedSecret, step + 1);

        JsonNode again = BrowserSession.json(s.api("POST", "/v1/admin/data-protection/rotate", admin, null).body());
        assertThat(again.get("resealed").asInt()).as("idempotent").isZero();
        assertThat(jdbc.sql("select count(*) from access_change_log where action = 'data-protection.rotated'")
                .query(Long.class).single()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private record StoredTotp(String plaintext, byte[] ciphertext, String keyId) {
    }

    private StoredTotp stored(UUID userId) {
        return jdbc.sql("select secret, secret_ciphertext, secret_key_id from mfa_totp where user_id = :u")
                .param("u", userId)
                .query((rs, n) -> new StoredTotp(rs.getString(1), rs.getBytes(2), rs.getString(3))).single();
    }

    private void insertSealed(UUID userId, String secret, SecretCipher cipher) {
        SecretCipher.Sealed sealed = cipher.seal(secret, TestSecretCiphers.totpContext(userId));
        jdbc.sql("""
                insert into mfa_totp (user_id, secret_ciphertext, secret_key_id, created_at, confirmed_at,
                                      last_used_step)
                values (:u, :ct, :kid, now(), now(), 0)
                on conflict (user_id) do update set secret = null, secret_ciphertext = excluded.secret_ciphertext,
                    secret_key_id = excluded.secret_key_id, last_used_step = 0
                """).param("u", userId).param("ct", sealed.envelope()).param("kid", sealed.keyId()).update();
    }

    /**
     * Scans every table of the schema, row by row as text (bytea renders as hex), for the secret and its UTF-8 hex
     * form: a database dump must not contain it.
     */
    private void assertNotInDatabase(String secret) {
        String hex = HexFormat.of().formatHex(secret.getBytes(StandardCharsets.UTF_8));
        List<String> tables = jdbc.sql("""
                select quote_ident(table_name) from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE'
                """).query(String.class).list();
        assertThat(tables).contains("mfa_totp", "user_account", "platform_outbox", "access_change_log");
        for (String table : tables) {
            long hits = jdbc.sql("select count(*) from " + table + " t where strpos(t::text, :s) > 0 "
                            + "or strpos(lower(t::text), :h) > 0")
                    .param("s", secret).param("h", hex).query(Long.class).single();
            assertThat(hits).as("plaintext TOTP secret found in table %s", table).isZero();
        }
    }

    private void signInWithTotp(String email, String secret, long step) {
        BrowserSession browser = new BrowserSession(base());
        AuthorizeResult first = browser.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD);
        assertThat(first.mfaRequired()).isTrue();
        String code = browser.completeMfa("TOTP", TOTP.codeAt(secret, step), false);
        Tokens tokens = browser.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                new AuthorizeResult(code, false, first.verifier()));
        assertThat(tokens.status()).as(tokens.body()).isEqualTo(200);
    }

    private String adminToken() {
        BrowserSession admin = new BrowserSession(base());
        AuthorizeResult first = admin.signIn(STAFF_CONSOLE, STAFF_REDIRECT, ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(first.mfaRequired()).isTrue();
        admin.requestSmsCode();
        String code = admin.completeMfa("SMS", latestCode(ADMIN_PHONE), false);
        Tokens t = admin.exchangePublic(STAFF_CONSOLE, STAFF_REDIRECT, new AuthorizeResult(code, false,
                first.verifier()));
        assertThat(t.accessClaims().get("roles").toString()).contains("platform.super_admin");
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
