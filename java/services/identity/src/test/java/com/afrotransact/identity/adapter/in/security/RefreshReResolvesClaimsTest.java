package com.afrotransact.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.identity.application.TokenClaims;
import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.PrincipalType;
import com.afrotransact.identity.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

/**
 * The org claim is the only tenant credential the resource servers see (no OpenFGA in production). It is rebuilt from
 * the membership tables on every issuance, refresh included, so a removed member or a disabled account loses it at
 * the next refresh: the worst-case window is the access token lifetime (5 minutes), not the refresh token lifetime.
 */
class RefreshReResolvesClaimsTest {

    private static final String ORG = "org_3k2j9x8q7w6e5r4t3y2u";
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private final UUID id = UUID.randomUUID();
    private final AtomicReference<TokenClaims> stored = new AtomicReference<>();

    private UserAccount user(boolean enabled) {
        return new UserAccount(id, "Ada", "Okafor", "ada@example.com", true, null, "h", enabled, false, NOW, ORG, NOW,
                NOW, 0);
    }

    private Map<String, Object> refresh(OAuth2TokenType type) {
        IdentityTokenCustomizer customizer = new IdentityTokenCustomizer(subject -> Optional.ofNullable(stored.get()));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(id.toString());
        JwtEncodingContext context = JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), claims)
                .principal(UsernamePasswordAuthenticationToken.authenticated(id.toString(), null, Set.of()))
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizedScopes(Set.of("openid"))
                .tokenType(type)
                .build();
        customizer.customize(context);
        return claims.build().getClaims();
    }

    @Test
    void removedMemberGetsNoOrgClaimAndNoSellerPermissionsOnTheNextRefresh() {
        stored.set(new TokenClaims(id, user(true), new EffectiveAccess(PrincipalType.SELLER_MEMBER, Optional.of(ORG),
                Set.of("seller.owner"), Set.of("payouts:write"), false)));
        assertThat(refresh(OAuth2TokenType.ACCESS_TOKEN)).containsEntry("org", ORG);

        // membership removed: identity now resolves the account as a plain customer
        stored.set(new TokenClaims(id, user(true), new EffectiveAccess(PrincipalType.CUSTOMER, Optional.empty(),
                Set.of(), Set.of(), false)));
        Map<String, Object> after = refresh(OAuth2TokenType.ACCESS_TOKEN);
        assertThat(after).doesNotContainKey("org").containsEntry("pt", "CUSTOMER");
        assertThat(after.get("perms").toString()).doesNotContain("payouts:write");
    }

    @Test
    void aDisabledAccountCannotRefreshAtAll() {
        stored.set(new TokenClaims(id, user(false), new EffectiveAccess(PrincipalType.CUSTOMER, Optional.empty(),
                Set.of(), Set.of(), false)));
        assertThatThrownBy(() -> refresh(OAuth2TokenType.ACCESS_TOKEN)).isInstanceOf(OAuth2AuthenticationException.class);
    }
}
