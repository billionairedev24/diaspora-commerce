package com.afrotransact.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.afrotransact.identity.domain.ClientReconcileMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/** F15: scope and redirect-URI changes in YAML reach an existing client; secrets stay as they are unless rotating. */
class ClientReconcilerTest {

    private JdbcRegisteredClientRepository repo;
    private ClientReconciler reconciler;

    /** A stand-in encoder that marks hashes, so tests can tell a re-hash from the stored value. */
    private static final PasswordEncoder ENCODER = new PasswordEncoder() {
        @Override
        public String encode(CharSequence raw) {
            return "{hash}" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            return encoded.equals("{hash}" + raw);
        }
    };

    @BeforeEach
    void setUp() {
        repo = mock(JdbcRegisteredClientRepository.class);
        reconciler = new ClientReconcilerImpl(repo, ENCODER);
    }

    private static RegisteredClient stored(String secret, Set<String> scopes, Set<String> redirects) {
        RegisteredClient.Builder b = RegisteredClient.withId("id-1").clientId("web").clientName("Web (edited in staff console)")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientAuthenticationMethod(secret == null ? ClientAuthenticationMethod.NONE
                        : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(7)).build());
        if (secret != null) {
            b.clientSecret(secret);
        }
        redirects.forEach(b::redirectUri);
        scopes.forEach(b::scope);
        return b.build();
    }

    private static IdentityProperties.Client configured(String secret, List<String> scopes, List<String> redirects) {
        return new IdentityProperties.Client("web", "Web", secret == null ? "public" : "confidential", secret,
                List.of("authorization_code"), redirects, List.of(), scopes, false, false, null, null);
    }

    @Test
    void reportOnlyReportsAndNeverSaves() {
        when(repo.findByClientId("web")).thenReturn(stored(null, Set.of("openid"), Set.of("https://a/cb")));
        ClientReconciler.Outcome o = reconciler.reconcile(
                configured(null, List.of("openid", "mcp:seller"), List.of("https://a/cb", "https://b/cb")),
                ClientReconcileMode.REPORT, false);
        assertThat(o.drift()).isTrue();
        assertThat(o.updated()).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void additiveAddsScopesAndRedirectsButKeepsEverythingElse() {
        when(repo.findByClientId("web")).thenReturn(stored("{hash}secret-1", Set.of("openid", "legacy"),
                Set.of("https://a/cb")));
        ClientReconciler.Outcome o = reconciler.reconcile(
                configured("secret-1", List.of("openid", "mcp:seller"), List.of("https://a/cb", "https://b/cb")),
                ClientReconcileMode.ADDITIVE, false);
        assertThat(o.updated()).isTrue();
        assertThat(o.secretRotated()).isFalse();
        ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repo).save(saved.capture());
        RegisteredClient c = saved.getValue();
        assertThat(c.getScopes()).containsExactlyInAnyOrder("openid", "legacy", "mcp:seller");
        assertThat(c.getRedirectUris()).containsExactlyInAnyOrder("https://a/cb", "https://b/cb");
        // everything the staff console owns is untouched, secret included
        assertThat(c.getId()).isEqualTo("id-1");
        assertThat(c.getClientSecret()).isEqualTo("{hash}secret-1");
        assertThat(c.getClientName()).isEqualTo("Web (edited in staff console)");
        assertThat(c.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(c.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    void syncRemovesWhatTheConfigurationNoLongerLists() {
        when(repo.findByClientId("web")).thenReturn(stored(null, Set.of("openid", "mcp:staff"), Set.of("https://a/cb")));
        ClientReconciler.Outcome o = reconciler.reconcile(configured(null, List.of("openid"), List.of("https://a/cb")),
                ClientReconcileMode.SYNC, false);
        assertThat(o.updated()).isTrue();
        ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getScopes()).containsExactly("openid");
    }

    @Test
    void aSecondRunFindsNothingToDo() {
        RegisteredClient before = stored(null, Set.of("openid"), Set.of("https://a/cb"));
        when(repo.findByClientId("web")).thenReturn(before);
        IdentityProperties.Client cfg = configured(null, List.of("openid", "mcp:seller"), List.of("https://a/cb"));
        reconciler.reconcile(cfg, ClientReconcileMode.ADDITIVE, false);
        ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repo).save(saved.capture());
        when(repo.findByClientId("web")).thenReturn(saved.getValue());
        ClientReconciler.Outcome again = reconciler.reconcile(cfg, ClientReconcileMode.ADDITIVE, false);
        assertThat(again.drift()).isFalse();
        assertThat(again.updated()).isFalse();
        verify(repo, times(1)).save(any());
    }

    @Test
    void secretsAreNotTouchedWithoutRotationEvenWhenTheyDiffer() {
        when(repo.findByClientId("web")).thenReturn(stored("{hash}old", Set.of("openid"), Set.of("https://a/cb")));
        ClientReconciler.Outcome o = reconciler.reconcile(configured("new", List.of("openid"), List.of("https://a/cb")),
                ClientReconcileMode.SYNC, false);
        assertThat(o.updated()).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void rotationRehashesOnlyWhenAskedAndOnlyWhenTheSecretChanged() {
        when(repo.findByClientId("web")).thenReturn(stored("{hash}old", Set.of("openid"), Set.of("https://a/cb")));
        ClientReconciler.Outcome o = reconciler.reconcile(configured("new", List.of("openid"), List.of("https://a/cb")),
                ClientReconcileMode.REPORT, true);
        assertThat(o.secretRotated()).isTrue();
        ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getClientSecret()).isEqualTo("{hash}new");
        assertThat(saved.getValue().getScopes()).containsExactly("openid");

        when(repo.findByClientId("web")).thenReturn(saved.getValue());
        assertThat(reconciler.reconcile(configured("new", List.of("openid"), List.of("https://a/cb")),
                ClientReconcileMode.REPORT, true).updated()).isFalse();
    }

    @Test
    void aMissingClientIsNotTheReconcilersBusiness() {
        when(repo.findByClientId("web")).thenReturn(null);
        assertThat(reconciler.reconcile(configured(null, List.of("openid"), List.of()), ClientReconcileMode.SYNC, true))
                .isEqualTo(new ClientReconciler.Outcome(false, false, false));
        verify(repo, never()).save(any());
    }

    @Test
    void publicClientsMayNotCarryStaffScopesOnProdAndUat() {
        IdentityProperties.Client mcp = configured(null, List.of("openid", "mcp:staff"), List.of());
        List<String> forbidden = List.of("mcp:staff");
        assertThatThrownBy(() -> StartupProvisioning.checkPublicClientScopes(Set.of("prod"), mcp, forbidden))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mcp:staff");
        assertThatThrownBy(() -> StartupProvisioning.checkPublicClientScopes(Set.of("uat"), mcp, forbidden))
                .isInstanceOf(IllegalStateException.class);
        StartupProvisioning.checkPublicClientScopes(Set.of("dev"), mcp, forbidden);
        StartupProvisioning.checkPublicClientScopes(Set.of("local"), mcp, forbidden);
        // a confidential client may hold it
        StartupProvisioning.checkPublicClientScopes(Set.of("prod"),
                configured("s", List.of("mcp:staff"), List.of()), forbidden);
    }
}
