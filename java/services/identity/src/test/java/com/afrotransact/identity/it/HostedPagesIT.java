package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * The hosted pages that make identity the single place credentials are entered (SSO for the storefront, Seller Center
 * and the staff console through gateway-bff): branded sign-in and sign-up, CSRF, sign-up + email code continuing the
 * authorization request, prompt=create / prompt=login, single sign-on across apps, RP-initiated logout and the error
 * page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
@Testcontainers
class HostedPagesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String BFF = "gateway-bff";
    private static final String BFF_SECRET = "local-bff-secret";
    private static final String STOREFRONT_CALLBACK = "http://localhost:3000/api/bff/v1/auth/oidc/callback";
    private static final String SELLER_CALLBACK = "http://localhost:3001/api/bff/v1/auth/oidc/callback";
    private static final String STAFF_CALLBACK = "http://localhost:3002/api/bff/v1/auth/oidc/callback";
    private static final String PASSWORD = "Plantain-Jollof-2026";
    private static final String STOREFRONT_SIGNED_OUT = "http://localhost:3000/api/bff/v1/auth/signed-out/storefront";
    private static final String SELLER_SIGNED_OUT = "http://localhost:3001/api/bff/v1/auth/signed-out/seller-center";
    private static final String STAFF_SIGNED_OUT = "http://localhost:3002/api/bff/v1/auth/signed-out/staff-console";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    HostedPagesIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void signInPageIsBrandedPerAppAndUsesTheDesignTokens() {
        BrowserSession shop = new BrowserSession(base());
        String page = shop.get(login(shop, authorize(STOREFRONT_CALLBACK, "", "v1"))).body();
        assertThat(page).contains("Sign in to AfroTransact", "The flavours of home, a click away.",
                "/assets/tokens.css", "/assets/fonts.css", "/assets/auth.css", "name=\"_csrf\"",
                "Sign in with a passkey", "New here? Create an account", "Forgot password?",
                "autocomplete=\"username webauthn\"");
        // Only configured providers are offered (none in this test).
        assertThat(page).doesNotContain("Continue with Google", "Continue with Apple", "Continue with Facebook");

        BrowserSession staff = new BrowserSession(base());
        String staffPage = staff.get(login(staff, authorize(STAFF_CALLBACK, "", "v2"))).body();
        assertThat(staffPage).contains("Sign in to the staff console", "Staff console")
                .doesNotContain("Create an account");

        BrowserSession seller = new BrowserSession(base());
        assertThat(seller.get(login(seller, authorize(SELLER_CALLBACK, "", "v3"))).body())
                .contains("Sign in to Seller Center", "Create an account");

        assertThat(new BrowserSession(base()).get(base() + "/assets/tokens.css").body()).contains("--at-gold-500");
        assertThat(new BrowserSession(base()).get(base() + "/login/forgot").body())
                .contains("Forgot your password?", "Send code", "Contact support", "http://localhost:3000/help");
    }

    /**
     * Pages opened without an authorization request (a bookmark, a stale logout, the "forgot password" link of a page
     * whose request expired) are neutral AfroTransact pages: never the staff console's (nor another app's) branding.
     */
    @Test
    void pagesWithoutAnAppAreNeutralAndStaffBrandingNeedsTheStaffConsole() {
        for (String path : List.of("/login", "/login?logout", "/login/forgot", "/signup")) {
            String page = new BrowserSession(base()).get(base() + path).body();
            assertThat(page).as(path).contains("data-app=\"neutral\"", "One account for all of AfroTransact.")
                    .doesNotContain("Staff console", "@afrotransact.com account", "Seller Center");
        }
        // Each app's pages, all the way to "forgot password", keep that app's branding.
        BrowserSession seller = new BrowserSession(base());
        seller.get(login(seller, authorize(SELLER_CALLBACK, "", "f1")));
        assertThat(seller.get(base() + "/login/forgot").body()).contains("data-app=\"seller\"", "Seller Center")
                .doesNotContain("Staff console");
        BrowserSession staff = new BrowserSession(base());
        staff.get(login(staff, authorize(STAFF_CALLBACK, "", "f2")));
        assertThat(staff.get(base() + "/login/forgot").body()).contains("data-app=\"staff\"", "Staff console",
                "Two-step verification is still required", "ask an access administrator");
        assertThat(staff.get(base() + "/signup").body()).contains("data-app=\"staff\"")
                .doesNotContain("Create account");
        // The error page carries no app branding at all.
        assertThat(new BrowserSession(base()).get(base() + "/oauth2/authorize?response_type=code&client_id=nope")
                .body()).contains("Something went wrong").doesNotContain("Staff console", "data-app");
    }

    @Test
    void formsRequireTheCsrfToken() {
        BrowserSession s = new BrowserSession(base());
        s.get(base() + "/login");
        HttpResponse<String> forged = s.form("/login", Map.of("username", "admin@afrotransact.com",
                "password", "LocalAdmin2026!"));
        assertThat(forged.statusCode()).isEqualTo(403);
        HttpResponse<String> forgedSignUp = s.form("/signup", Map.of("email", "x@example.com"));
        assertThat(forgedSignUp.statusCode()).isEqualTo(403);
    }

    @Test
    void createAccountWithPromptCreateVerifiesTheEmailThenContinuesTheAuthorizationSignedIn() {
        BrowserSession s = new BrowserSession(base());
        String email = "ama." + UUID.randomUUID() + "@example.com";
        String verifier = BrowserSession.randomVerifier();
        HttpResponse<String> start = s.get(authorizeUrl(STOREFRONT_CALLBACK, "&prompt=create&login_hint="
                + enc(email), verifier));
        assertThat(BrowserSession.location(start)).endsWith("/signup");
        HttpResponse<String> page = s.get(base() + "/signup");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Create your AfroTransact account", "value=\"" + email + "\"",
                "Mobile phone", "(optional)", "Conditions of Use", "Privacy Notice", "12+ characters",
                "/legal/conditions-of-use\"", "/legal/privacy\"", "/legal/sms-terms\"");

        // Field errors come back on the page, values kept (never the password).
        Map<String, String> bad = signUpForm(BrowserSession.csrfOf(page.body()), email);
        bad.put("password", "short");
        bad.put("confirmPassword", "short");
        bad.remove("termsAccepted");
        HttpResponse<String> invalid = s.form("/signup", bad);
        assertThat(invalid.statusCode()).isEqualTo(200);
        assertThat(invalid.body()).contains("data-error-summary", "Use at least 12 characters", "value=\"Ama\"")
                .doesNotContain("value=\"short\"");

        HttpResponse<String> created = s.form("/signup", signUpForm(BrowserSession.csrfOf(invalid.body()), email));
        assertThat(BrowserSession.location(created)).endsWith("/signup/verify");
        HttpResponse<String> verify = s.get(base() + "/signup/verify");
        assertThat(verify.body()).contains("Confirm your email", "a••••••@example.com");

        HttpResponse<String> wrong = s.form("/signup/verify", Map.of("code", "000000",
                "_csrf", BrowserSession.csrfOf(verify.body())));
        assertThat(wrong.statusCode()).isEqualTo(200);
        assertThat(wrong.body()).contains("aria-invalid=\"true\"");

        HttpResponse<String> verified = s.form("/signup/verify", Map.of("code", latestCode(email),
                "_csrf", BrowserSession.csrfOf(wrong.body())));
        String resumed = BrowserSession.location(verified);
        assertThat(resumed).contains("/oauth2/authorize").contains("prompt=create");
        HttpResponse<String> issued = s.get(resumed.startsWith("http") ? resumed : base() + resumed);
        String code = BrowserSession.code(BrowserSession.location(issued));
        assertThat(BrowserSession.location(issued)).startsWith(STOREFRONT_CALLBACK);

        Tokens tokens = s.exchangeConfidential(BFF, BFF_SECRET, STOREFRONT_CALLBACK, code, verifier);
        assertThat(tokens.status()).as(tokens.body()).isEqualTo(200);
        assertThat(BrowserSession.claims(tokens.idToken()).get("email").asString()).isEqualTo(email);
        assertThat(tokens.accessClaims().get("pt").asString()).isEqualTo("CUSTOMER");
    }

    @Test
    void startSellingSignUpIsBrandedForSellersAndAddsTheApplicantRole() {
        BrowserSession s = new BrowserSession(base());
        String email = "kofi." + UUID.randomUUID() + "@example.com";
        String verifier = BrowserSession.randomVerifier();
        HttpResponse<String> start = s.get(authorizeUrl(SELLER_CALLBACK, "&prompt=create&intent=sell", verifier));
        assertThat(BrowserSession.location(start)).endsWith("/signup");
        HttpResponse<String> page = s.get(base() + "/signup");
        assertThat(page.body()).contains("Create your account to start selling", "Already have an account? Sign in",
                "We’ll take you straight to your seller application", "Seller Center");

        HttpResponse<String> created = s.form("/signup", signUpForm(BrowserSession.csrfOf(page.body()), email));
        assertThat(BrowserSession.location(created)).endsWith("/signup/verify");
        HttpResponse<String> verified = s.form("/signup/verify", Map.of("code", latestCode(email),
                "_csrf", BrowserSession.csrfOf(s.get(base() + "/signup/verify").body())));
        String resumed = BrowserSession.location(verified);
        HttpResponse<String> issued = s.get(resumed.startsWith("http") ? resumed : base() + resumed);
        assertThat(BrowserSession.location(issued)).startsWith(SELLER_CALLBACK + "?code=");
        assertThat(jdbc.sql("""
                select r.role_key from user_role r join user_account u on u.id = r.user_id where u.email = :e
                """).param("e", email).query(String.class).list())
                .containsExactlyInAnyOrder("account.customer", "account.seller_applicant");

        // "Offer your services" has its own copy; an unknown intent (or one from another app) is ignored.
        BrowserSession provider = new BrowserSession(base());
        provider.get(authorizeUrl(SELLER_CALLBACK, "&prompt=create&intent=provide", BrowserSession.randomVerifier()));
        assertThat(provider.get(base() + "/signup").body()).contains("Create your account to offer your services",
                "Grow your bookings with the diaspora near you.");
        BrowserSession shopper = new BrowserSession(base());
        shopper.get(authorizeUrl(STOREFRONT_CALLBACK, "&prompt=create&intent=sell", BrowserSession.randomVerifier()));
        assertThat(shopper.get(base() + "/signup").body()).contains("Create your AfroTransact account")
                .doesNotContain("start selling");
    }

    @Test
    void signingInOnceSignsInEveryAppUntilRpInitiatedLogout() {
        BrowserSession s = new BrowserSession(base());
        String email = verifiedAccount();

        // Storefront: the hosted sign-in page, then back to the storefront's callback with a code.
        String verifier = BrowserSession.randomVerifier();
        String loginPage = BrowserSession.location(s.get(authorizeUrl(STOREFRONT_CALLBACK, "", verifier)));
        HttpResponse<String> page = s.get(absolute(loginPage));
        HttpResponse<String> posted = s.form("/login", Map.of("username", email, "password", PASSWORD,
                "_csrf", BrowserSession.csrfOf(page.body())));
        HttpResponse<String> issued = s.get(absolute(BrowserSession.location(posted)));
        assertThat(BrowserSession.location(issued)).startsWith(STOREFRONT_CALLBACK + "?code=");
        Tokens shop = s.exchangeConfidential(BFF, BFF_SECRET, STOREFRONT_CALLBACK,
                BrowserSession.code(BrowserSession.location(issued)), verifier);
        assertThat(shop.status()).as(shop.body()).isEqualTo(200);

        // Seller Center: same browser, no credential prompt (single sign-on).
        String sellerVerifier = BrowserSession.randomVerifier();
        String seller = BrowserSession.location(s.get(authorizeUrl(SELLER_CALLBACK, "", sellerVerifier)));
        assertThat(seller).startsWith(SELLER_CALLBACK + "?code=");
        assertThat(s.exchangeConfidential(BFF, BFF_SECRET, SELLER_CALLBACK, BrowserSession.code(seller),
                sellerVerifier).status()).isEqualTo(200);

        // RP-initiated logout (what the BFF's sign-out sends the browser to) ends the identity session and returns
        // to the signed-out landing of the app that signed out, with the BFF's state (the page to come back to).
        HttpResponse<String> logout = s.get(base() + "/connect/logout?id_token_hint=" + enc(shop.idToken())
                + "&client_id=" + BFF + "&post_logout_redirect_uri=" + enc(STAFF_SIGNED_OUT) + "&state=%2Forders");
        assertThat(BrowserSession.location(logout)).isEqualTo(STAFF_SIGNED_OUT + "?state=%2Forders");
        assertThat(BrowserSession.location(s.get(authorizeUrl(STAFF_CALLBACK, "", BrowserSession.randomVerifier()))))
                .endsWith("/login");

        // Signed in again: prompt=login asks once more even with an identity session; signing in continues the same
        // request.
        HttpResponse<String> back = s.form("/login", Map.of("username", email, "password", PASSWORD,
                "_csrf", BrowserSession.csrfOf(s.get(base() + "/login").body())));
        assertThat(BrowserSession.location(s.get(absolute(BrowserSession.location(back)))))
                .startsWith(STAFF_CALLBACK + "?code=");
        String again = BrowserSession.location(s.get(authorizeUrl(SELLER_CALLBACK, "&prompt=login",
                BrowserSession.randomVerifier())));
        assertThat(again).endsWith("/login");
        HttpResponse<String> relogin = s.form("/login", Map.of("username", email, "password", PASSWORD,
                "_csrf", BrowserSession.csrfOf(s.get(base() + "/login").body())));
        assertThat(BrowserSession.location(s.get(absolute(BrowserSession.location(relogin)))))
                .startsWith(SELLER_CALLBACK + "?code=");

        // A logout whose ID token no longer matches the session (it was rotated by signing in again) still signs
        // out and returns to a registered page.
        HttpResponse<String> stale = s.get(base() + "/connect/logout?id_token_hint=" + enc(shop.idToken())
                + "&client_id=" + BFF + "&post_logout_redirect_uri=" + enc(SELLER_SIGNED_OUT));
        assertThat(BrowserSession.location(stale)).isEqualTo(SELLER_SIGNED_OUT);
        assertThat(BrowserSession.location(s.get(authorizeUrl(SELLER_CALLBACK, "", BrowserSession.randomVerifier()))))
                .endsWith("/login");
    }

    /**
     * Single sign-out from each web app: identity validates the post-logout URI registered for that app and sends the
     * browser there (the BFF then shows the storefront signed out, or starts that app's own sign-in). The sign-in the
     * BFF starts right after (prompt=login, notice=signed_out) is that app's branded page saying so.
     */
    @Test
    void eachAppSignsOutToItsOwnRegisteredLandingAndItsOwnBrandedSignIn() {
        String email = verifiedAccount();
        Map<String, String> apps = Map.of(STOREFRONT_CALLBACK, STOREFRONT_SIGNED_OUT, SELLER_CALLBACK,
                SELLER_SIGNED_OUT, STAFF_CALLBACK, STAFF_SIGNED_OUT);
        apps.forEach((callback, signedOut) -> {
            BrowserSession s = new BrowserSession(base());
            Tokens t = signInTo(s, email, callback);
            HttpResponse<String> logout = s.get(base() + "/connect/logout?id_token_hint=" + enc(t.idToken())
                    + "&client_id=" + BFF + "&post_logout_redirect_uri=" + enc(signedOut));
            assertThat(BrowserSession.location(logout)).as(callback).isEqualTo(signedOut);
            // Signed out of identity too: the next authorization asks for credentials again.
            assertThat(BrowserSession.location(s.get(authorizeUrl(callback, "", BrowserSession.randomVerifier()))))
                    .endsWith("/login");
        });

        BrowserSession seller = new BrowserSession(base());
        String page = seller.get(login(seller, authorize(SELLER_CALLBACK, "&prompt=login&notice=signed_out", "so")))
                .body();
        assertThat(page).contains("Sign in to Seller Center", "You’ve been signed out.").doesNotContain("Staff console");
        BrowserSession staff = new BrowserSession(base());
        assertThat(staff.get(login(staff, authorize(STAFF_CALLBACK, "&prompt=login&notice=signed_out", "st"))).body())
                .contains("Sign in to the staff console", "You’ve been signed out.");

        // A post-logout URI that is not registered for the client (the old home pages) is never followed: the
        // browser is signed out and lands on identity's neutral signed-out page.
        BrowserSession other = new BrowserSession(base());
        Tokens t = signInTo(other, email, SELLER_CALLBACK);
        HttpResponse<String> foreign = other.get(base() + "/connect/logout?id_token_hint=" + enc(t.idToken())
                + "&client_id=" + BFF + "&post_logout_redirect_uri=" + enc("http://localhost:3001/"));
        assertThat(BrowserSession.location(foreign)).endsWith("/login?logout");
        assertThat(other.get(base() + "/login?logout").body()).contains("You’ve been signed out.",
                "data-app=\"neutral\"").doesNotContain("Staff console");
    }

    private Tokens signInTo(BrowserSession s, String email, String callback) {
        String verifier = BrowserSession.randomVerifier();
        String loginPage = BrowserSession.location(s.get(authorizeUrl(callback, "", verifier)));
        HttpResponse<String> page = s.get(absolute(loginPage));
        HttpResponse<String> posted = s.form("/login", Map.of("username", email, "password", PASSWORD,
                "_csrf", BrowserSession.csrfOf(page.body())));
        String issued = BrowserSession.location(s.get(absolute(BrowserSession.location(posted))));
        assertThat(issued).startsWith(callback + "?code=");
        Tokens t = s.exchangeConfidential(BFF, BFF_SECRET, callback, BrowserSession.code(issued), verifier);
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t;
    }

    @Test
    void invalidAuthorizationRequestsShowTheBrandedErrorPage() {
        HttpResponse<String> r = new BrowserSession(base()).get(base()
                + "/oauth2/authorize?response_type=code&client_id=no-such-client&scope=openid");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("Something went wrong", "/assets/tokens.css").doesNotContain("Whitelabel");
        // A stale logout link still signs the browser out and lands on the signed-out page.
        HttpResponse<String> stale = new BrowserSession(base()).get(base() + "/connect/logout?id_token_hint=bogus");
        assertThat(BrowserSession.location(stale)).endsWith("/login?logout");
    }

    private String verifiedAccount() {
        BrowserSession s = new BrowserSession(base());
        String email = "kofi." + UUID.randomUUID() + "@example.com";
        s.get(base() + "/signup");
        HttpResponse<String> created = s.form("/signup", signUpForm(BrowserSession.csrfOf(
                s.get(base() + "/signup").body()), email));
        assertThat(BrowserSession.location(created)).endsWith("/signup/verify");
        HttpResponse<String> verified = s.form("/signup/verify", Map.of("code", latestCode(email),
                "_csrf", BrowserSession.csrfOf(s.get(base() + "/signup/verify").body())));
        // No pending authorization request: the new account lands on the home page, signed in.
        assertThat(BrowserSession.location(verified)).isEqualTo("http://localhost:3000");
        return email;
    }

    private String authorize(String redirectUri, String extra, String seed) {
        return authorizeUrl(redirectUri, extra, BrowserSession.randomVerifier() + seed);
    }

    private String authorizeUrl(String redirectUri, String extra, String verifier) {
        return base() + "/oauth2/authorize?response_type=code&client_id=" + BFF + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("openid profile email offline_access") + "&state=st-" + UUID.randomUUID()
                + "&nonce=n-1&code_challenge=" + BrowserSession.challenge(verifier) + "&code_challenge_method=S256"
                + extra;
    }

    private String login(BrowserSession s, String authorizeUrl) {
        String location = BrowserSession.location(s.get(authorizeUrl));
        assertThat(location).endsWith("/login");
        return absolute(location);
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : base() + location;
    }

    private static Map<String, String> signUpForm(String csrf, String email) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("firstName", "Ama");
        form.put("lastName", "Mensah");
        form.put("email", email);
        form.put("phoneRegion", "US");
        form.put("phone", "");
        form.put("password", PASSWORD);
        form.put("confirmPassword", PASSWORD);
        form.put("termsAccepted", "true");
        form.put("_csrf", csrf);
        return form;
    }

    private String latestCode(String email) {
        return jdbc.sql("""
                select payload->>'code' from platform_outbox
                where type = 'identity.verification.requested' and payload->'recipient'->>'email' = :e
                order by occurred_at desc limit 1
                """).param("e", email).query(String.class).single();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
