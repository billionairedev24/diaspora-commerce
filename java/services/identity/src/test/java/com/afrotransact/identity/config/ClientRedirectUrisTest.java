package com.afrotransact.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Local LAN mode (make up MOBILE_HOST=lan): the mobile clients' redirect URIs gain Expo Go's exp:// callbacks through
 * a placeholder that is empty in localhost mode, so an entry may hold zero, one or several comma-separated URIs.
 */
class ClientRedirectUrisTest {

    private static IdentityProperties.Client client(List<String> redirects, List<String> postLogout) {
        return new IdentityProperties.Client("customer-app", null, "public", null, List.of("authorization_code"),
                redirects, postLogout, List.of("openid"), false, true, null, null);
    }

    @Test
    void anEmptyPlaceholderAddsNothing() {
        IdentityProperties.Client c = client(List.of("com.afrotransact.customer:/oauth2redirect",
                "http://localhost:8081/auth/callback", ""), null);
        assertThat(c.redirectUris()).containsExactly("com.afrotransact.customer:/oauth2redirect",
                "http://localhost:8081/auth/callback");
        assertThat(c.postLogoutRedirectUris()).isEmpty();
    }

    @Test
    void aPlaceholderMayCarrySeveralCommaSeparatedUris() {
        IdentityProperties.Client c = client(List.of("com.afrotransact.customer:/oauth2redirect",
                " exp://192.168.1.50:8081/--/auth/callback , http://192.168.1.50:8081/auth/callback",
                "com.afrotransact.customer:/oauth2redirect"), List.of("http://192.168.1.50:3000/,"));
        assertThat(c.redirectUris()).containsExactly("com.afrotransact.customer:/oauth2redirect",
                "exp://192.168.1.50:8081/--/auth/callback", "http://192.168.1.50:8081/auth/callback");
        assertThat(c.postLogoutRedirectUris()).containsExactly("http://192.168.1.50:3000/");
    }

    @Test
    void nullEntriesAreIgnored() {
        assertThat(client(Arrays.asList("a:/b", null), null).redirectUris()).containsExactly("a:/b");
    }
}
