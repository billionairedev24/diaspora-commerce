package com.afrotransact.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.application.TokenClaims;
import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.PrincipalType;
import com.afrotransact.identity.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

class IdentityTokenCustomizerTest {

    private static final String ORG = "org_3k2j9x8q7w6e5r4t3y2u";

    private static UserAccount user(UUID id, String phone, boolean verified) {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        return new UserAccount(id, "Adaeze", "Okafor", "ada@example.com", verified, phone, "{bcrypt}x", true, false,
                now, ORG, now, now, 0);
    }

    private static Map<String, Object> issue(UserAccount u, OAuth2TokenType type, Set<String> scopes) {
        return issue(u, type, scopes, Set.of());
    }

    private static Map<String, Object> issue(UserAccount u, OAuth2TokenType type, Set<String> scopes,
                                             Set<GrantedAuthority> authorities) {
        EffectiveAccess access = new EffectiveAccess(PrincipalType.SELLER_MEMBER, Optional.of(ORG),
                Set.of("seller.owner"), Set.of("catalog:write"), false);
        IdentityTokenCustomizer customizer = new IdentityTokenCustomizer(
                subject -> Optional.of(new TokenClaims(u.id(), u, access)));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(u.id().toString());
        JwtEncodingContext context = JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), claims)
                .principal(UsernamePasswordAuthenticationToken.authenticated(u.id().toString(), null, authorities))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopes)
                .tokenType(type)
                .build();
        customizer.customize(context);
        return claims.build().getClaims();
    }

    @Test
    void accessTokensCarryProfileClaimsAndTheSellerOrgId() {
        UserAccount u = user(UUID.randomUUID(), "+17132490142", true);
        Map<String, Object> c = issue(u, OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"));
        assertThat(c).containsEntry("given_name", "Adaeze")
                .containsEntry("family_name", "Okafor")
                .containsEntry("email", "ada@example.com")
                .containsEntry("email_verified", true)
                .containsEntry("phone_number", "+17132490142")
                .containsEntry("org", ORG)
                .containsEntry("pt", "SELLER_MEMBER");
        assertThat(c.get("perms").toString()).contains("catalog:write");
    }

    @Test
    void accessTokensCarryTheLatestSignInTimeAsAuthTimeForStepUp() {
        Instant password = Instant.parse("2026-10-01T09:58:00Z");
        Instant secondFactor = Instant.parse("2026-10-01T09:59:30Z");
        Set<GrantedAuthority> factors = Set.of(
                FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY).issuedAt(password)
                        .build(),
                FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
                        .issuedAt(secondFactor).build());
        UserAccount u = user(UUID.randomUUID(), null, true);

        assertThat(issue(u, OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"), factors))
                .containsEntry("auth_time", Date.from(secondFactor));
        // no recorded sign-in: no claim, so a step-up proof is needed
        assertThat(issue(u, OAuth2TokenType.ACCESS_TOKEN, Set.of("openid"))).doesNotContainKey("auth_time");
    }

    @Test
    void phoneNumberIsOmittedWhenTheAccountHasNone() {
        Map<String, Object> c = issue(user(UUID.randomUUID(), null, false), OAuth2TokenType.ACCESS_TOKEN, Set.of());
        assertThat(c).doesNotContainKey("phone_number").containsEntry("email_verified", false)
                .containsEntry("given_name", "Adaeze");
    }

    @Test
    void idTokensCarryProfileClaimsForGrantedScopes() {
        UserAccount u = user(UUID.randomUUID(), "+17132490142", true);
        Map<String, Object> c = issue(u, new OAuth2TokenType(OidcParameterNames.ID_TOKEN),
                Set.of("openid", "profile", "email", "phone"));
        assertThat(c).containsEntry("given_name", "Adaeze")
                .containsEntry("family_name", "Okafor")
                .containsEntry("email", "ada@example.com")
                .containsEntry("email_verified", true)
                .containsEntry("phone_number", "+17132490142")
                .containsEntry("org", ORG)
                .doesNotContainKey("perms");
    }
}
