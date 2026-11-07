package com.afrotransact.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.afrotransact.identity.adapter.in.security.HostedPageContentSecurityPolicy;
import com.afrotransact.identity.adapter.in.security.SavedRequestFormActionTargets;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The central security headers ({@link IdentitySecurityHeaders} + {@link HostedPageContentSecurityPolicy}) applied by a
 * real Spring Security filter chain to the hosted page URLs and a real static asset, without the database.
 */
@SpringJUnitWebConfig(SecurityHeadersTest.TestApp.class)
class SecurityHeadersTest {

    private static final String CALLBACK = "https://app.example.test/callback";

    private final RequestCache requestCache;
    private final MockMvc mvc;

    @Autowired
    SecurityHeadersTest(WebApplicationContext context, RequestCache requestCache) {
        this.requestCache = requestCache;
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void everyHostedPageCarriesTheStrictPolicy() throws Exception {
        for (String page : List.of("/login", "/signup", "/login/mfa", "/error")) {
            MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get(page)).andReturn().getResponse();
            assertThat(response.getHeader("Content-Security-Policy")).as(page)
                    .isEqualTo("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                            + "font-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; "
                            + "object-src 'none'; form-action 'self' https://home.example.test");
            assertThat(response.getHeader("Cache-Control")).as(page).contains("no-store");
            assertBaselineHeaders(response, page);
        }
    }

    @Test
    void staticAssetsCarryTheBaselineHeadersToo() throws Exception {
        MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get("/assets/auth.css")).andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Content-Security-Policy")).startsWith("default-src 'none'");
        assertBaselineHeaders(response, "/assets/auth.css");
    }

    @Test
    void hstsOnlyOverHttps() throws Exception {
        MockHttpServletResponse plain = mvc.perform(MockMvcRequestBuilders.get("/login")).andReturn().getResponse();
        assertThat(plain.getHeader("Strict-Transport-Security")).isNull();
        MockHttpServletResponse secure = mvc.perform(MockMvcRequestBuilders.get("/login").secure(true)).andReturn()
                .getResponse();
        assertThat(secure.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000 ; includeSubDomains");
    }

    @Test
    void passkeysStayAllowedOnTheHostedPagesAndNothingElsePowerful() throws Exception {
        String policy = mvc.perform(MockMvcRequestBuilders.get("/login")).andReturn().getResponse()
                .getHeader("Permissions-Policy");
        assertThat(policy).contains("publickey-credentials-get=(self)", "camera=()", "microphone=()",
                "geolocation=()", "payment=()");
    }

    @Test
    void apiReferencePagesAreExemptBecauseTheyAreRenderedByThirdPartyScripts() throws Exception {
        for (String page : List.of("/docs", "/redoc", "/swagger-ui.html", "/swagger-ui/index.html")) {
            MvcResult result = mvc.perform(MockMvcRequestBuilders.get(page)).andReturn();
            assertThat(result.getResponse().getHeader("Content-Security-Policy")).as(page).isNull();
            assertThat(result.getResponse().getHeader("X-Content-Type-Options")).as(page).isEqualTo("nosniff");
        }
    }

    @Test
    void formActionFollowsTheRegisteredRedirectOfThePendingSignIn() throws Exception {
        MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get("/login")
                .session(sessionWithPendingAuthorization(CALLBACK))).andReturn().getResponse();
        assertThat(response.getHeader("Content-Security-Policy"))
                .endsWith("form-action 'self' https://home.example.test https://app.example.test");
    }

    @Test
    void anUnregisteredRedirectUriNeverWidensFormAction() throws Exception {
        MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get("/login")
                .session(sessionWithPendingAuthorization("https://evil.example.test/callback"))).andReturn()
                .getResponse();
        assertThat(response.getHeader("Content-Security-Policy")).endsWith("form-action 'self' https://home.example.test")
                .doesNotContain("evil");
    }

    @Test
    void sourcesAreOriginsOrCustomSchemesAndNothingInjectable() {
        assertThat(SavedRequestFormActionTargets.source("https://App.Example.test:8443/cb?x=1")).isEqualTo(
                "https://app.example.test:8443");
        assertThat(SavedRequestFormActionTargets.source("http://127.0.0.1:3000/cb")).isEqualTo("http://127.0.0.1:3000");
        assertThat(SavedRequestFormActionTargets.source("com.afrotransact.customer:/oauth")).isEqualTo(
                "com.afrotransact.customer:");
        assertThat(SavedRequestFormActionTargets.source("javascript:alert(1)")).isNull();
        assertThat(SavedRequestFormActionTargets.source("https://a.test; script-src *")).isNull();
        assertThat(SavedRequestFormActionTargets.source("/relative")).isNull();
        assertThat(SavedRequestFormActionTargets.source(null)).isNull();
    }

    private MockHttpSession sessionWithPendingAuthorization(String redirectUri) {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest authorize =
                new MockHttpServletRequest("GET", "/oauth2/authorize");
        authorize.setSession(session);
        authorize.setParameter("client_id", "web");
        authorize.setParameter("redirect_uri", redirectUri);
        requestCache.saveRequest(authorize, new MockHttpServletResponse());
        return session;
    }

    private static void assertBaselineHeaders(MockHttpServletResponse response, String page) {
        assertThat(response.getHeader("X-Content-Type-Options")).as(page).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).as(page).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader("X-Frame-Options")).as(page).isEqualTo("DENY");
        assertThat(response.getHeader("Permissions-Policy")).as(page).isNotBlank();
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class TestApp implements WebMvcConfigurer {

        @Bean
        RequestCache requestCache() {
            return new HttpSessionRequestCache();
        }

        @Bean
        RegisteredClientRepository clients() {
            return new InMemoryRegisteredClientRepository(RegisteredClient.withId("1").clientId("web")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(CALLBACK).build());
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity http, RequestCache requestCache, RegisteredClientRepository clients)
                throws Exception {
            IdentitySecurityHeaders.apply(http, new HostedPageContentSecurityPolicy(
                    new SavedRequestFormActionTargets(requestCache, clients, "https://home.example.test/")));
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }

        @Bean
        Pages pages() {
            return new Pages();
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/assets/**").addResourceLocations("file:src/main/resources/static/assets/");
        }
    }

    @RestController
    static class Pages {

        @GetMapping({"/login", "/signup", "/login/mfa", "/error", "/docs", "/redoc", "/swagger-ui.html",
                "/swagger-ui/index.html"})
        String page() {
            return "<html></html>";
        }
    }
}
