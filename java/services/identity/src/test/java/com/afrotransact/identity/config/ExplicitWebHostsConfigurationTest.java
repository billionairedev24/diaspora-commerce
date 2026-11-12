package com.afrotransact.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.afrotransact.identity.adapter.in.security.SavedRequestFormActionTargets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

/**
 * The free certificate layout (docs/engineering/environments.md, "Free certificate layout (flat web hosts)"): the Helm
 * library chart injects the storefront, Seller Center and staff console origins, and the Seller Center and staff console
 * hosts are not subdomains of the platform domain. Binds the real {@code application.yml} + {@code application-dev.yml}
 * against those variables: every registered redirect URI, post-logout URI, CORS origin and page link must be an exact
 * origin of the three apps, and the passkey relying party must stay the platform domain.
 */
class ExplicitWebHostsConfigurationTest {

    private static final String STOREFRONT = "https://test.example.test";
    private static final String SELLER = "https://seller-test.example.test";
    private static final String ADMIN = "https://admin-test.example.test";
    private static final String AUTH = "https://auth.test.example.test";

    private static IdentityProperties bind(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader yaml = new YamlPropertySourceLoader();
        List<PropertySource<?>> dev = yaml.load("dev", new ClassPathResource("application-dev.yml"));
        List<PropertySource<?>> base = yaml.load("base", new ClassPathResource("application.yml"));
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        dev.forEach(environment.getPropertySources()::addLast);
        base.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("at.identity", IdentityProperties.class).get();
    }

    private static Map<String, Object> flatHosts() {
        return Map.of("PUBLIC_WEB_URL", STOREFRONT, "SELLER_CENTER_URL", SELLER, "STAFF_CONSOLE_URL", ADMIN,
                "AUTH_ISSUER_URL", AUTH, "PLATFORM_DOMAIN", "test.example.test");
    }

    private static IdentityProperties.Client gatewayBff(IdentityProperties props) {
        return props.clients().stream().filter(c -> c.clientId().equals("gateway-bff")).findFirst().orElseThrow();
    }

    @Test
    void theGatewayClientRegistersExactlyTheThreeExplicitOrigins() throws IOException {
        IdentityProperties.Client client = gatewayBff(bind(flatHosts()));
        assertThat(client.redirectUris()).containsExactlyInAnyOrder(
                STOREFRONT + "/api/bff/v1/auth/oidc/callback",
                SELLER + "/api/bff/v1/auth/oidc/callback",
                ADMIN + "/api/bff/v1/auth/oidc/callback");
        assertThat(client.postLogoutRedirectUris()).containsExactlyInAnyOrder(
                STOREFRONT + "/api/bff/v1/auth/signed-out/storefront",
                SELLER + "/api/bff/v1/auth/signed-out/seller-center",
                ADMIN + "/api/bff/v1/auth/signed-out/staff-console");
    }

    @Test
    void corsPagesHomeAndInvitationFollowTheExplicitHostsWithoutWildcards() throws IOException {
        IdentityProperties props = bind(flatHosts());
        assertThat(props.corsAllowedOrigins()).containsExactly(STOREFRONT, SELLER, ADMIN);
        assertThat(props.corsAllowedOrigins()).noneMatch(o -> o.contains("*"));
        assertThat(props.homeUrl()).isEqualTo(STOREFRONT);
        assertThat(props.pages().storefrontUrl()).isEqualTo(STOREFRONT);
        assertThat(props.pages().sellerCenterUrl()).isEqualTo(SELLER);
        assertThat(props.pages().staffConsoleUrl()).isEqualTo(ADMIN);
        assertThat(props.invitations().acceptUrl()).isEqualTo(SELLER + "/invitations/accept");
        assertThat(props.issuer()).isEqualTo(AUTH);
    }

    @Test
    void passkeysStayOnThePlatformDomainAndNeverOnTheFlatHosts() throws IOException {
        IdentityProperties.WebAuthn webauthn = bind(flatHosts()).webauthn();
        // Hosted sign-in runs on auth.<base> and the storefront at the base: both are the RP id or below it.
        assertThat(webauthn.rpId()).isEqualTo("test.example.test");
        assertThat(webauthn.allowedOrigins()).containsExactly(AUTH, STOREFRONT);
        // seller-test.* and admin-test.* are not below the RP id, a ceremony there would be refused by the browser;
        // they do not run one (sign-in is the OIDC redirect to identity), so they are not listed.
        assertThat(webauthn.allowedOrigins()).doesNotContain(SELLER, ADMIN);
        for (String origin : webauthn.allowedOrigins()) {
            String host = origin.substring("https://".length());
            assertThat(host.equals(webauthn.rpId()) || host.endsWith("." + webauthn.rpId())).as(origin).isTrue();
        }
    }

    @Test
    void withoutTheVariablesTheDevHostsAreUnchanged() throws IOException {
        IdentityProperties props = bind(Map.of());
        assertThat(props.pages().sellerCenterUrl()).isEqualTo("https://seller-dev.afrotransact.com");
        assertThat(props.pages().staffConsoleUrl()).isEqualTo("https://admin-dev.afrotransact.com");
        assertThat(gatewayBff(props).redirectUris()).containsExactlyInAnyOrder(
                "https://dev.afrotransact.com/api/bff/v1/auth/oidc/callback",
                "https://seller-dev.afrotransact.com/api/bff/v1/auth/oidc/callback",
                "https://admin-dev.afrotransact.com/api/bff/v1/auth/oidc/callback");
        assertThat(props.webauthn().rpId()).isEqualTo("dev.afrotransact.com");
    }

    @Test
    void formActionAllowsTheExplicitSellerOriginOnlyForItsRegisteredCallback() {
        RequestCache cache = new HttpSessionRequestCache();
        InMemoryRegisteredClientRepository clients = new InMemoryRegisteredClientRepository(
                RegisteredClient.withId("1").clientId("gateway-bff")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri(STOREFRONT + "/api/bff/v1/auth/oidc/callback")
                        .redirectUri(SELLER + "/api/bff/v1/auth/oidc/callback")
                        .redirectUri(ADMIN + "/api/bff/v1/auth/oidc/callback").build());
        SavedRequestFormActionTargets targets = new SavedRequestFormActionTargets(cache, clients, STOREFRONT);

        assertThat(sources(targets, cache, SELLER + "/api/bff/v1/auth/oidc/callback"))
                .containsExactly(STOREFRONT, SELLER);
        assertThat(sources(targets, cache, ADMIN + "/api/bff/v1/auth/oidc/callback"))
                .containsExactly(STOREFRONT, ADMIN);
        // The default host (seller.<base>) is not registered once the explicit host is: never allowed.
        assertThat(sources(targets, cache, "https://seller.test.example.test/api/bff/v1/auth/oidc/callback"))
                .containsExactly(STOREFRONT);
    }

    private static List<String> sources(SavedRequestFormActionTargets targets, RequestCache cache, String redirectUri) {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
        authorize.setSession(session);
        authorize.setParameter("client_id", "gateway-bff");
        authorize.setParameter("redirect_uri", redirectUri);
        cache.saveRequest(authorize, new MockHttpServletResponse());
        MockHttpServletRequest login = new MockHttpServletRequest("GET", "/login");
        login.setSession(session);
        return targets.sources(login, new MockHttpServletResponse());
    }
}
