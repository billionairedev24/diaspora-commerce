package com.afrotransact.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SavedRequestFilterTest {

    private static MockHttpServletRequest get(String uri, String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        return request;
    }

    @Test
    void theAuthorizationRequestABrowserOpensIsRemembered() {
        assertThat(SecurityConfiguration.worthReturningTo(
                get("/oauth2/authorize", "text/html,application/xhtml+xml,*/*;q=0.8"))).isTrue();
    }

    @Test
    void chromeDevToolsProbeIsNeverRemembered() {
        // Chrome fetches this on every page while DevTools is open; it used to replace /oauth2/authorize (404 after sign-up).
        assertThat(SecurityConfiguration.worthReturningTo(
                get("/.well-known/appspecific/com.chrome.devtools.json", "text/html,*/*"))).isFalse();
        assertThat(SecurityConfiguration.worthReturningTo(
                get("/.well-known/appspecific/com.chrome.devtools.json", "*/*"))).isFalse();
    }

    @Test
    void assetsFaviconPostsAndNonHtmlCallsAreNotRemembered() {
        assertThat(SecurityConfiguration.worthReturningTo(get("/assets/auth.css", "text/css,*/*"))).isFalse();
        assertThat(SecurityConfiguration.worthReturningTo(get("/favicon.ico", "image/*"))).isFalse();
        assertThat(SecurityConfiguration.worthReturningTo(get("/v1/me", "application/json"))).isFalse();
        assertThat(SecurityConfiguration.worthReturningTo(get("/oauth2/authorize", null))).isFalse();
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/oauth2/authorize");
        post.addHeader("Accept", "text/html");
        assertThat(SecurityConfiguration.worthReturningTo(post)).isFalse();
    }
}
