package com.afrotransact.identity.adapter.out.crypto;

import java.util.List;
import java.util.Map;

import com.afrotransact.identity.port.DiscoveryReader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/**
 * The documents a client discovers this server by: the OIDC discovery document (built from the same endpoint settings
 * the authorization server serves) and the JWKS, which is always the public keys only.
 */
public class PublishedKeys implements DiscoveryReader {

    private final JWKSource<SecurityContext> keys;
    private final AuthorizationServerSettings settings;
    private final List<String> scopes;

    public PublishedKeys(JWKSource<SecurityContext> keys, AuthorizationServerSettings settings, List<String> scopes) {
        this.keys = keys;
        this.settings = settings;
        this.scopes = List.copyOf(scopes);
    }

    @Override
    public Map<String, Object> discovery() {
        String issuer = settings.getIssuer();
        return Map.ofEntries(
                Map.entry("issuer", issuer),
                Map.entry("authorization_endpoint", issuer + settings.getAuthorizationEndpoint()),
                Map.entry("token_endpoint", issuer + settings.getTokenEndpoint()),
                Map.entry("jwks_uri", issuer + settings.getJwkSetEndpoint()),
                Map.entry("userinfo_endpoint", issuer + settings.getOidcUserInfoEndpoint()),
                Map.entry("revocation_endpoint", issuer + settings.getTokenRevocationEndpoint()),
                Map.entry("end_session_endpoint", issuer + settings.getOidcLogoutEndpoint()),
                Map.entry("response_types_supported", List.of("code")),
                Map.entry("grant_types_supported", List.of("authorization_code", "refresh_token", "client_credentials")),
                Map.entry("subject_types_supported", List.of("public")),
                Map.entry("id_token_signing_alg_values_supported", List.of("RS256")),
                Map.entry("code_challenge_methods_supported", List.of("S256")),
                Map.entry("token_endpoint_auth_methods_supported",
                        List.of("client_secret_basic", "client_secret_post", "none")),
                Map.entry("scopes_supported", scopes));
    }

    @Override
    public Map<String, Object> jwks() {
        try {
            List<JWK> all = keys.get(new JWKSelector(new JWKMatcher.Builder().build()), null);
            return new JWKSet(all).toJSONObject(true);
        } catch (KeySourceException ex) {
            throw new IllegalStateException("The signing keys cannot be read", ex);
        }
    }
}
