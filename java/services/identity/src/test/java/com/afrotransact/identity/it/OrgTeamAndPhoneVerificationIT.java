package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * S231 organisation member directory, role changes and invitations, and S234 phone verification, over real HTTP with
 * real PostgreSQL: who may read and change the team, last-owner protection, role-portal checks, invitation tokens
 * (hash only stored, single use, 7 days, replaced on re-invite, revoked), accepting signed in or by signing up from
 * the link, the outbox events, and SMS phone verification with attempt limits, expiry, rate limit and reset on change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox",
    "at.identity.invitations.accept-url=http://localhost:3001/invitations/accept"})
@ActiveProfiles("local")
@Testcontainers
class OrgTeamAndPhoneVerificationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String STOREFRONT = "storefront";
    private static final String STOREFRONT_REDIRECT = "http://localhost:3000/auth/callback";
    private static final String PASSWORD = "Plantain-Jollof-2026";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    OrgTeamAndPhoneVerificationIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void memberDirectoryInvitationsAndRoleChanges() {
        BrowserSession s = new BrowserSession(base());
        String seller = s.clientCredentials("seller-service", "local-seller-secret", "orgs:manage").accessToken();
        String orgId = "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        assertThat(s.api("PUT", "/v1/orgs/" + orgId, seller, "{\"kind\":\"SELLER\",\"name\":\"Mama Put\","
                + "\"status\":\"APPROVED\"}").statusCode()).isEqualTo(200);

        String ownerEmail = "owner." + UUID.randomUUID() + "@example.com";
        String ownerId = registerAndVerify(s, ownerEmail, "Ada", null);
        assertThat(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + ownerId, seller, "{\"roleKey\":\"seller.owner\"}")
                .statusCode()).isEqualTo(204);
        String owner = userToken(s, ownerEmail);

        // Directory: names, email, role, status, joined date.
        HttpResponse<String> dir = s.api("GET", "/v1/orgs/" + orgId + "/members", owner, null);
        assertThat(dir.statusCode()).as(dir.body()).isEqualTo(200);
        JsonNode first = BrowserSession.json(dir.body()).get("items").get(0);
        assertThat(first.get("userId").asString()).isEqualTo(ownerId);
        assertThat(first.get("name").asString()).isEqualTo("Ada Okafor");
        assertThat(first.get("email").asString()).isEqualTo(ownerEmail);
        assertThat(first.get("roleKey").asString()).isEqualTo("seller.owner");
        assertThat(first.get("roleLabel").asString()).isEqualTo("Owner");
        assertThat(first.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(first.get("joinedAt").asString()).isNotBlank();

        // S245: the role picker lists seller roles for a SELLER organisation, both sets once it is BOTH.
        JsonNode sellerRoles = BrowserSession.json(s.api("GET", "/v1/orgs/" + orgId + "/roles", owner, null).body());
        assertThat(sellerRoles.get("kind").asString()).isEqualTo("SELLER");
        List<String> portals = new ArrayList<>();
        sellerRoles.get("roles").forEach(r -> portals.add(r.get("portal").asString()));
        assertThat(portals).isNotEmpty().containsOnly("seller");
        assertThat(s.api("PUT", "/v1/orgs/" + orgId, seller, "{\"kind\":\"BOTH\",\"name\":\"Mama Put\","
                + "\"status\":\"APPROVED\"}").statusCode()).isEqualTo(200);
        JsonNode bothRoles = BrowserSession.json(s.api("GET", "/v1/orgs/" + orgId + "/roles", owner, null).body());
        assertThat(bothRoles.get("kind").asString()).isEqualTo("BOTH");
        List<String> keys = new ArrayList<>();
        bothRoles.get("roles").forEach(r -> keys.add(r.get("key").asString()));
        assertThat(keys).contains("seller.owner", "seller.catalog", "provider.owner", "provider.practitioner");
        assertThat(s.api("PUT", "/v1/orgs/" + orgId, seller, "{\"kind\":\"SELLER\",\"name\":\"Mama Put\","
                + "\"status\":\"APPROVED\"}").statusCode()).isEqualTo(200);

        // Invite an existing customer as catalog manager; only the token hash is stored.
        String bolaEmail = "bola." + UUID.randomUUID() + "@example.com";
        String bolaId = registerAndVerify(s, bolaEmail, "Bola", null);
        assertThat(s.api("POST", "/v1/orgs/" + orgId + "/invitations", owner,
                invite(bolaEmail, "provider.practitioner")).statusCode()).as("seller org, provider role").isEqualTo(422);
        HttpResponse<String> created = s.api("POST", "/v1/orgs/" + orgId + "/invitations", owner,
                invite(bolaEmail.toUpperCase(), "seller.catalog"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode inv = BrowserSession.json(created.body());
        assertThat(inv.get("email").asString()).isEqualTo(bolaEmail);
        assertThat(inv.get("status").asString()).isEqualTo("PENDING");
        assertThat(inv.get("roleLabel").asString()).isEqualTo("Catalog manager");
        assertThat(Duration.between(Instant.parse(inv.get("createdAt").asString()),
                Instant.parse(inv.get("expiresAt").asString()))).isEqualTo(Duration.ofDays(7));
        String firstToken = invitationToken(bolaEmail);
        JsonNode event = latestInvitationEvent(bolaEmail);
        assertThat(event.get("acceptUrl").asString())
                .isEqualTo("http://localhost:3001/invitations/accept?token=" + firstToken);
        assertThat(event.get("recipient").get("email").asString()).isEqualTo(bolaEmail);
        assertThat(event.get("orgName").asString()).isEqualTo("Mama Put");
        assertThat(event.get("roleKey").asString()).isEqualTo("seller.catalog");
        assertThat(event.get("inviterName").asString()).isEqualTo("Ada Okafor");
        assertThat(jdbc.sql("select count(*) from org_invitation where token_hash = :t").param("t", firstToken)
                .query(Integer.class).single()).as("plain token never stored").isZero();

        // Inviting again replaces the open invitation (resend); the old link stops working.
        assertThat(s.api("POST", "/v1/orgs/" + orgId + "/invitations", owner, invite(bolaEmail, "seller.catalog"))
                .statusCode()).isEqualTo(201);
        String token = invitationToken(bolaEmail);
        assertThat(token).isNotEqualTo(firstToken);
        JsonNode pending = BrowserSession.json(s.api("GET", "/v1/orgs/" + orgId + "/invitations", owner, null).body());
        assertThat(pending.get("items").size()).isEqualTo(1);
        assertThat(lookup(s, firstToken).get("status").asString()).isEqualTo("REVOKED");
        JsonNode preview = lookup(s, token);
        assertThat(preview.get("status").asString()).isEqualTo("PENDING");
        assertThat(preview.get("orgName").asString()).isEqualTo("Mama Put");
        assertThat(preview.get("email").asString()).isEqualTo(bolaEmail);
        assertThat(s.api("POST", "/v1/invitations/lookup", null, tokenBody("nope")).statusCode()).isEqualTo(404);

        // Someone else cannot use it; the invitee accepts once.
        String otherEmail = "other." + UUID.randomUUID() + "@example.com";
        registerAndVerify(s, otherEmail, "Kemi", null);
        HttpResponse<String> mismatch = s.api("POST", "/v1/invitations/accept", userToken(s, otherEmail),
                tokenBody(token));
        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(code(mismatch)).isEqualTo("identity.invitation-email-mismatch");
        String bola = userToken(s, bolaEmail);
        HttpResponse<String> accepted = s.api("POST", "/v1/invitations/accept", bola, tokenBody(token));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(BrowserSession.json(accepted.body()).get("roleKey").asString()).isEqualTo("seller.catalog");
        assertThat(BrowserSession.json(accepted.body()).get("orgId").asString()).isEqualTo(orgId);
        HttpResponse<String> again = s.api("POST", "/v1/invitations/accept", bola, tokenBody(token));
        assertThat(code(again)).isEqualTo("identity.invitation-used");
        assertThat(outbox(bolaId, "identity.role.granted")).anyMatch(p -> p.contains("seller.catalog")
                && p.contains(orgId));
        assertThat(outbox(bolaId, "identity.invitation.accepted")).hasSize(1);
        assertThat(s.api("POST", "/v1/orgs/" + orgId + "/invitations", owner, invite(bolaEmail, "seller.admin"))
                .statusCode()).as("already a member").isEqualTo(409);

        // Sign-up from the invite link: no email code, verified at once, joins the organisation.
        String newEmail = "new." + UUID.randomUUID() + "@example.com";
        assertThat(s.api("POST", "/v1/orgs/" + orgId + "/invitations", owner, invite(newEmail, "seller.support"))
                .statusCode()).isEqualTo(201);
        String newToken = invitationToken(newEmail);
        HttpResponse<String> wrongEmail = s.api("POST", "/v1/accounts", null, signUp("wrong." + newEmail, "Tobi",
                newToken));
        assertThat(wrongEmail.statusCode()).isEqualTo(422);
        assertThat(code(wrongEmail)).isEqualTo("identity.invitation-email-mismatch");
        HttpResponse<String> signedUp = s.api("POST", "/v1/accounts", null, signUp(newEmail, "Tobi", newToken));
        assertThat(signedUp.statusCode()).as(signedUp.body()).isEqualTo(201);
        assertThat(BrowserSession.json(signedUp.body()).get("verificationRequired").asBoolean()).isFalse();
        String tobiId = BrowserSession.json(signedUp.body()).get("userId").asString();
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'identity.verification.requested' "
                + "and payload->>'userId' = :u").param("u", tobiId).query(Integer.class).single()).isZero();
        Tokens tobi = userTokens(newEmail);
        assertThat(tobi.accessClaims().get("org").asString()).isEqualTo(orgId);
        assertThat(tobi.accessClaims().get("roles").toString()).contains("seller.support");

        JsonNode members = BrowserSession.json(s.api("GET", "/v1/orgs/" + orgId + "/members", owner, null).body())
                .get("items");
        List<String> names = new ArrayList<>();
        members.forEach(m -> names.add(m.get("name").asString() + ":" + m.get("roleKey").asString()));
        assertThat(names).containsExactly("Ada Okafor:seller.owner", "Bola Okafor:seller.catalog",
                "Tobi Okafor:seller.support");

        // A catalog manager has neither team:manage nor Team & roles access.
        assertThat(s.api("GET", "/v1/orgs/" + orgId + "/members", bola, null).statusCode()).isEqualTo(403);
        assertThat(s.api("POST", "/v1/orgs/" + orgId + "/invitations", bola, invite("x@example.com",
                "seller.catalog")).statusCode()).isEqualTo(403);
        assertThat(s.api("GET", "/v1/orgs/" + orgId + "/members", userToken(s, otherEmail), null).statusCode())
                .as("not a member").isEqualTo(403);
        String payments = s.clientCredentials("payments-service", "local-payments-service-secret", "orgs:read")
                .accessToken();
        assertThat(s.api("GET", "/v1/orgs/" + orgId + "/members", payments, null).statusCode())
                .as("services with orgs:read").isEqualTo(200);
        assertThat(s.api("GET", "/v1/orgs/org_unknown0000000000/members", owner, null).statusCode()).isEqualTo(404);
        assertThat(s.api("GET", "/v1/orgs/" + orgId + "/roles", userToken(s, otherEmail), null).statusCode())
                .as("role list: same readers as the directory").isEqualTo(403);

        // Role changes: the owner makes Bola an admin (revoked + granted events); admins cannot touch owners.
        assertThat(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + bolaId, owner,
                "{\"roleKey\":\"provider.manager\"}").statusCode()).as("wrong portal").isEqualTo(422);
        assertThat(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + bolaId, owner, "{\"roleKey\":\"seller.admin\"}")
                .statusCode()).isEqualTo(204);
        assertThat(outbox(bolaId, "identity.role.revoked")).anyMatch(p -> p.contains("seller.catalog"));
        assertThat(outbox(bolaId, "identity.role.granted")).anyMatch(p -> p.contains("seller.admin"));
        String bolaAdmin = s.refreshPublic(STOREFRONT, userTokens(bolaEmail).refreshToken()).accessToken();
        assertThat(s.api("GET", "/v1/orgs/" + orgId + "/members", bolaAdmin, null).statusCode()).isEqualTo(200);
        assertThat(code(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + ownerId, bolaAdmin,
                "{\"roleKey\":\"seller.admin\"}"))).isEqualTo("identity.owner-only");
        assertThat(code(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + tobiId, bolaAdmin,
                "{\"roleKey\":\"seller.owner\"}"))).isEqualTo("identity.owner-only");
        assertThat(code(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + bolaId, bolaAdmin,
                "{\"roleKey\":\"seller.catalog\"}"))).isEqualTo("identity.own-role");
        assertThat(code(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + UUID.randomUUID(), bolaAdmin,
                "{\"roleKey\":\"seller.catalog\"}"))).isEqualTo("identity.member-not-found");

        // Revoke: admins revoke pending invitations; a revoked one cannot be revoked or accepted.
        String danEmail = "dan." + UUID.randomUUID() + "@example.com";
        String danInvitation = BrowserSession.json(s.api("POST", "/v1/orgs/" + orgId + "/invitations", bolaAdmin,
                invite(danEmail, "seller.finance")).body()).get("id").asString();
        assertThat(s.api("DELETE", "/v1/orgs/" + orgId + "/invitations/" + danInvitation, bolaAdmin, null)
                .statusCode()).isEqualTo(204);
        assertThat(s.api("DELETE", "/v1/orgs/" + orgId + "/invitations/" + danInvitation, bolaAdmin, null)
                .statusCode()).isEqualTo(409);
        assertThat(lookup(s, invitationToken(danEmail)).get("status").asString()).isEqualTo("REVOKED");
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'identity.invitation.revoked' "
                + "and aggregate_id = :o").param("o", orgId).query(Integer.class).single()).isEqualTo(2);
        assertThat(BrowserSession.json(s.api("GET", "/v1/orgs/" + orgId + "/invitations", owner, null).body())
                .get("items").size()).isZero();

        // Removal and the last owner.
        assertThat(s.api("DELETE", "/v1/orgs/" + orgId + "/members/" + tobiId, bolaAdmin, null).statusCode())
                .isEqualTo(204);
        assertThat(outbox(tobiId, "identity.role.revoked")).anyMatch(p -> p.contains("seller.support"));
        HttpResponse<String> lastOwner = s.api("DELETE", "/v1/orgs/" + orgId + "/members/" + ownerId, owner, null);
        assertThat(lastOwner.statusCode()).isEqualTo(422);
        assertThat(code(lastOwner)).isEqualTo("identity.last-owner");
        assertThat(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + bolaId, owner, "{\"roleKey\":\"seller.owner\"}")
                .statusCode()).isEqualTo(204);
        assertThat(s.api("DELETE", "/v1/orgs/" + orgId + "/members/" + ownerId, owner, null).statusCode())
                .as("another owner remains").isEqualTo(204);
        List<String> left = jdbc.sql("select role_key from org_membership where org_id = :o order by role_key")
                .param("o", orgId).query(String.class).list();
        assertThat(left).containsExactly("seller.owner");
    }

    @Test
    void phoneVerificationBySmsCode() {
        BrowserSession s = new BrowserSession(base());
        String email = "phone." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email, "Ada", null);
        String token = userToken(s, email);
        String notifications = s.clientCredentials("notifications-service", "local-notifications-service-secret",
                "users:read").accessToken();
        assertThat(contact(s, notifications, userId).get("phoneVerified").asBoolean()).isFalse();
        assertThat(BrowserSession.json(s.api("GET", "/v1/me", token, null).body()).get("phoneVerified").asBoolean())
                .isFalse();

        // Code 1: five wrong guesses use it up.
        HttpResponse<String> sent = s.api("POST", "/v1/me/phone/verification", token, null);
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(202);
        assertThat(BrowserSession.json(sent.body()).get("sentTo").asString()).isEqualTo("•••• 0142");
        JsonNode otp = latestPhoneCode(userId);
        assertThat(otp.get("channel").asString()).isEqualTo("sms");
        assertThat(otp.get("purpose").asString()).isEqualTo("PHONE_VERIFICATION");
        assertThat(otp.get("recipient").get("phone").asString()).isEqualTo("+17132490142");
        assertThat(otp.get("expiresInMinutes").asInt()).isEqualTo(10);
        String code1 = otp.get("code").asString();
        assertThat(jdbc.sql("select code_hash from verification_code where user_id = :u and purpose = "
                + "'PHONE_VERIFICATION'").param("u", UUID.fromString(userId)).query(String.class).single())
                .as("stored hashed").hasSize(64).isNotEqualTo(code1);
        assertThat(s.api("POST", "/v1/me/phone/verification", token, null).statusCode()).as("resend limit")
                .isEqualTo(429);
        String wrong = code1.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThat(code(confirm(s, token, wrong))).isEqualTo("identity.invalid-code");
        }
        assertThat(code(confirm(s, token, code1))).isEqualTo("identity.too-many-attempts");

        // Code 2: expires after 10 minutes.
        allowResend(userId);
        assertThat(s.api("POST", "/v1/me/phone/verification", token, null).statusCode()).isEqualTo(202);
        String code2 = latestPhoneCode(userId).get("code").asString();
        jdbc.sql("update verification_code set expires_at = now() - interval '1 second' where user_id = :u "
                + "and consumed_at is null").param("u", UUID.fromString(userId)).update();
        assertThat(code(confirm(s, token, code2))).isEqualTo("identity.code-expired");

        // Code 3: verifies.
        allowResend(userId);
        assertThat(s.api("POST", "/v1/me/phone/verification", token, null).statusCode()).isEqualTo(202);
        String code3 = latestPhoneCode(userId).get("code").asString();
        HttpResponse<String> ok = confirm(s, token, code3);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(BrowserSession.json(ok.body()).get("phoneVerified").asBoolean()).isTrue();
        assertThat(code(confirm(s, token, code3))).as("single use").isEqualTo("identity.code-used");
        assertThat(BrowserSession.json(s.api("GET", "/v1/me", token, null).body()).get("phoneVerified").asBoolean())
                .isTrue();
        assertThat(contact(s, notifications, userId).get("phoneVerified").asBoolean()).isTrue();
        assertThat(outbox(userId, "identity.user.phone-verified")).hasSize(1);
        allowResend(userId);
        assertThat(code(s.api("POST", "/v1/me/phone/verification", token, null)))
                .isEqualTo("identity.phone-already-verified");

        // Editing other fields keeps it; changing the number resets it, and changing back does not restore it.
        HttpResponse<String> renamed = s.api("PATCH", "/v1/me", token, "{\"firstName\":\"Adaora\"}");
        assertThat(BrowserSession.json(renamed.body()).get("phoneVerified").asBoolean()).isTrue();
        assertThat(s.api("PATCH", "/v1/me", token, "{\"phone\":\"+447911123456\"}").statusCode()).isEqualTo(200);
        assertThat(BrowserSession.json(s.api("GET", "/v1/me", token, null).body()).get("phoneVerified").asBoolean())
                .isFalse();
        assertThat(contact(s, notifications, userId).get("phoneVerified").asBoolean()).isFalse();
        assertThat(s.api("PATCH", "/v1/me", token, "{\"phone\":\"+17132490142\"}").statusCode()).isEqualTo(200);
        assertThat(contact(s, notifications, userId).get("phoneVerified").asBoolean()).isFalse();
        allowResend(userId);
        assertThat(s.api("POST", "/v1/me/phone/verification", token, null).statusCode()).as("verify again")
                .isEqualTo(202);
    }

    // ---------------------------------------------------------------- helpers

    private void allowResend(String userId) {
        jdbc.sql("update verification_code set created_at = created_at - interval '1 minute' where user_id = :u")
                .param("u", UUID.fromString(userId)).update();
    }

    private JsonNode latestPhoneCode(String userId) {
        return BrowserSession.json(jdbc.sql("""
                select payload::text from platform_outbox
                where type = 'identity.verification.requested' and payload->>'userId' = :u
                    and payload->>'purpose' = 'PHONE_VERIFICATION'
                order by occurred_at desc limit 1
                """).param("u", userId).query(String.class).single());
    }

    private JsonNode latestInvitationEvent(String email) {
        return BrowserSession.json(jdbc.sql("""
                select payload::text from platform_outbox
                where type = 'identity.invitation.created' and payload->>'email' = :e
                order by occurred_at desc limit 1
                """).param("e", email).query(String.class).single());
    }

    private String invitationToken(String email) {
        return latestInvitationEvent(email).get("token").asString();
    }

    private List<String> outbox(String key, String type) {
        return jdbc.sql("select payload::text from platform_outbox where aggregate_id = :k and type = :t")
                .param("k", key).param("t", type).query(String.class).list();
    }

    private static JsonNode lookup(BrowserSession s, String token) {
        HttpResponse<String> r = s.api("POST", "/v1/invitations/lookup", null, tokenBody(token));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return BrowserSession.json(r.body());
    }

    private static JsonNode contact(BrowserSession s, String service, String userId) {
        return BrowserSession.json(s.api("GET", "/v1/internal/users/" + userId + "/contact", service, null).body());
    }

    private static HttpResponse<String> confirm(BrowserSession s, String token, String code) {
        return s.api("POST", "/v1/me/phone/verification/confirm", token, "{\"code\":\"" + code + "\"}");
    }

    private static String code(HttpResponse<String> r) {
        JsonNode body = BrowserSession.json(r.body());
        return body.has("code") ? body.get("code").asString() : "HTTP " + r.statusCode() + " " + r.body();
    }

    private static String invite(String email, String role) {
        return "{\"email\":\"" + email + "\",\"roleKey\":\"" + role + "\"}";
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private static String signUp(String email, String firstName, String invitationToken) {
        return """
                {"firstName":"%s","lastName":"Okafor","email":"%s","phone":"(713) 249-0142","phoneRegion":"US",
                 "password":"%s","confirmPassword":"%s","termsAccepted":true,"smsConsent":false,"intent":"shop"%s}
                """.formatted(firstName, email, PASSWORD, PASSWORD,
                invitationToken == null ? "" : ",\"invitationToken\":\"" + invitationToken + "\"");
    }

    private String userToken(BrowserSession s, String email) {
        return userTokens(email).accessToken();
    }

    /** Signs in with a fresh cookie jar, so an earlier user's login session is not reused. */
    private Tokens userTokens(String email) {
        BrowserSession browser = new BrowserSession(base());
        Tokens t = browser.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                browser.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t;
    }

    private String registerAndVerify(BrowserSession s, String email, String firstName, String invitationToken) {
        HttpResponse<String> created = s.api("POST", "/v1/accounts", null, signUp(email, firstName, invitationToken));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String code = jdbc.sql("""
                select payload->>'code' from platform_outbox
                where type = 'identity.verification.requested' and payload->'recipient'->>'email' = :d
                order by occurred_at desc limit 1
                """).param("d", email).query(String.class).single();
        assertThat(s.api("POST", "/v1/accounts/verification", null,
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}").statusCode()).isEqualTo(204);
        return BrowserSession.json(created.body()).get("userId").asString();
    }
}
