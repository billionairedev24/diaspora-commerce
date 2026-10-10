package com.afrotransact.identity.it;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A browser-like HTTP client (cookies, manual redirects) that walks the real OAuth 2.1 authorization-code + PKCE flow
 * against the running authorization server: /oauth2/authorize → /login → (optional /login/mfa) → code → /oauth2/token.
 */
final class BrowserSession {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private final String base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    BrowserSession(String base) {
        this.base = base;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    CookieManager cookies() {
        return cookies;
    }

    /** Result of starting sign-in: either a code (signed in) or the MFA page is pending. */
    record AuthorizeResult(String code, boolean mfaRequired, String verifier) {
    }

    record Tokens(String accessToken, String refreshToken, String idToken, JsonNode accessClaims, int status,
                  String body) {
    }

    AuthorizeResult signIn(String clientId, String redirectUri, String email, String password) {
        String verifier = randomVerifier();
        String authorize = base + "/oauth2/authorize?response_type=code&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri) + "&scope=" + enc("openid profile email offline_access")
                + "&state=st-123&code_challenge=" + challenge(verifier) + "&code_challenge_method=S256";
        HttpResponse<String> start = get(authorize);
        expect(start, 302);
        String loginUrl = location(start);
        if (!loginUrl.contains("/login")) {
            return new AuthorizeResult(code(loginUrl), false, verifier);
        }
        HttpResponse<String> page = get(loginUrl);
        expect(page, 200);
        HttpResponse<String> posted = postForm(base + "/login", Map.of("username", email, "password", password,
                "_csrf", csrf(page.body())));
        expect(posted, 302);
        String next = location(posted);
        if (next.contains("/login/mfa")) {
            return new AuthorizeResult(null, true, verifier);
        }
        if (next.contains("/login?error")) {
            throw new IllegalStateException("Sign-in rejected: " + next);
        }
        HttpResponse<String> resumed = get(absolute(next));
        expect(resumed, 302);
        return new AuthorizeResult(code(location(resumed)), false, verifier);
    }

    /** Completes the second factor; returns the authorization code. */
    String completeMfa(String factor, String code, boolean trust) {
        HttpResponse<String> page = get(base + "/login/mfa");
        expect(page, 200);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("factor", factor);
        form.put("code", code);
        form.put("_csrf", csrf(page.body()));
        if (trust) {
            form.put("trust", "true");
        }
        HttpResponse<String> posted = postForm(base + "/login/mfa", form);
        expect(posted, 302);
        String next = location(posted);
        if (next.contains("/login/mfa")) {
            throw new IllegalStateException("Second factor rejected");
        }
        HttpResponse<String> resumed = get(absolute(next));
        expect(resumed, 302);
        return code(location(resumed));
    }

    /** Asks for an SMS sign-in code on the MFA page. */
    void requestSmsCode() {
        HttpResponse<String> page = get(base + "/login/mfa");
        expect(page, 200);
        HttpResponse<String> sent = postForm(base + "/login/mfa/sms", Map.of("_csrf", csrf(page.body())));
        expect(sent, 302);
    }

    Tokens exchangePublic(String clientId, String redirectUri, AuthorizeResult result) {
        return token(Map.of("grant_type", "authorization_code", "code", result.code(), "redirect_uri", redirectUri,
                "client_id", clientId, "code_verifier", result.verifier()), null);
    }

    Tokens refreshPublic(String clientId, String refreshToken) {
        return token(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id", clientId),
                null);
    }

    /** Confidential client (e.g. gateway-bff): code + PKCE verifier, HTTP Basic client authentication. */
    Tokens exchangeConfidential(String clientId, String secret, String redirectUri, String code, String verifier) {
        return token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirectUri,
                "code_verifier", verifier), basic(clientId, secret));
    }

    Tokens refreshConfidential(String clientId, String secret, String refreshToken) {
        return token(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken), basic(clientId, secret));
    }

    private static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret)
                .getBytes(StandardCharsets.UTF_8));
    }

    Tokens clientCredentials(String clientId, String secret, String scope) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return token(Map.of("grant_type", "client_credentials", "scope", scope), "Basic " + basic);
    }

    HttpResponse<String> get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/html").GET().build());
    }

    HttpResponse<String> api(String method, String path, String bearer, String jsonBody) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "application/json");
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody));
        return send(b.build());
    }

    /** CSRF token of the current browser session (rendered on the sign-in page). */
    String csrfToken() {
        HttpResponse<String> page = get(base + "/login");
        expect(page, 200);
        return csrf(page.body());
    }

    /** Same-origin JSON POST from the browser session (WebAuthn ceremonies). */
    HttpResponse<String> postJson(String path, String json, String csrfToken) {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    static JsonNode claims(String jwt) {
        String payload = jwt.split("\\.")[1];
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private Tokens token(Map<String, String> form, String authorization) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form)));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        HttpResponse<String> r = send(b.build());
        if (r.statusCode() != 200) {
            return new Tokens(null, null, null, null, r.statusCode(), r.body());
        }
        JsonNode body = JSON.readTree(r.body());
        String access = body.get("access_token").asString();
        return new Tokens(access, text(body, "refresh_token"), text(body, "id_token"), claims(access), 200, r.body());
    }

    private HttpResponse<String> postForm(String url, Map<String, String> form) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form))).build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : base + location;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    static String location(HttpResponse<String> r) {
        return r.headers().firstValue("Location").orElseThrow(() -> new IllegalStateException("No redirect: "
                + r.statusCode() + " " + r.body()));
    }

    static String code(String redirect) {
        Matcher m = Pattern.compile("[?&]code=([^&]+)").matcher(redirect);
        if (!m.find()) {
            throw new IllegalStateException("No authorization code in " + redirect);
        }
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private static String csrf(String html) {
        Matcher m = CSRF.matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("No CSRF token in page");
        }
        return m.group(1);
    }

    private static void expect(HttpResponse<String> r, int status) {
        if (r.statusCode() != status) {
            throw new IllegalStateException("Expected " + status + " but got " + r.statusCode() + " for "
                    + r.request().uri() + ": " + r.headers().map() + " " + r.body());
        }
    }

    private static String formBody(Map<String, String> form) {
        return form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String randomVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
