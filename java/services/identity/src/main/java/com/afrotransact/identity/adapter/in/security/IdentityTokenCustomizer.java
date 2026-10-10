package com.afrotransact.identity.adapter.in.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import com.afrotransact.identity.application.TokenClaims;
import com.afrotransact.identity.application.TokenClaimsService;
import com.afrotransact.identity.domain.PrincipalType;
import com.afrotransact.identity.domain.UserAccount;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Adds the platform claims every resource server reads ({@code JwtActorResolver}): {@code pt}, {@code org},
 * {@code roles}, {@code perms}. Claims are recomputed on every issuance, including refresh, so access changes take
 * effect on the next token (access tokens live 5 minutes). Disabled accounts cannot obtain tokens.
 */
public class IdentityTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    public static final String PRINCIPAL_TYPE = "pt";
    public static final String ORG = "org";
    public static final String ROLES = "roles";
    public static final String PERMS = "perms";

    private final TokenClaimsService tokenClaims;

    public IdentityTokenCustomizer(TokenClaimsService tokenClaims) {
        this.tokenClaims = tokenClaims;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        JwtClaimsSet.Builder claims = context.getClaims();
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
            claims.claim(PRINCIPAL_TYPE, PrincipalType.SERVICE.name());
            claims.claim(ROLES, new ArrayList<String>());
            claims.claim(PERMS, sorted(context.getAuthorizedScopes()));
            return;
        }
        Optional<TokenClaims> resolved = tokenClaims.forSubject(context.getPrincipal().getName());
        if (resolved.isEmpty()) {
            return;
        }
        TokenClaims c = resolved.get();
        if (!c.user().enabled()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                    "The account is disabled", null));
        }
        claims.claim(PRINCIPAL_TYPE, c.access().principalType().name());
        claims.claim(ROLES, sorted(c.access().roles()));
        c.access().orgId().ifPresent(org -> claims.claim(ORG, org.toString()));
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            claims.claim(PERMS, sorted(c.access().permissions()));
        } else if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
            addProfileClaims(claims, c.user(), context.getAuthorizedScopes());
        }
    }

    private static void addProfileClaims(JwtClaimsSet.Builder claims, UserAccount u, Collection<String> scopes) {
        if (scopes.contains(OidcScopes.EMAIL)) {
            claims.claim("email", u.email());
            claims.claim("email_verified", u.emailVerified());
        }
        if (scopes.contains(OidcScopes.PROFILE)) {
            claims.claim("name", u.displayName());
            claims.claim("given_name", u.firstName());
            claims.claim("family_name", u.lastName());
        }
        if (scopes.contains(OidcScopes.PHONE) && u.phone() != null) {
            claims.claim("phone_number", u.phone());
        }
    }

    /**
     * Claims are also stored in the authorization's token metadata and read back with Spring Security's Jackson
     * allow-list, which accepts {@link ArrayList} but not JDK immutable collections.
     */
    private static List<String> sorted(Collection<String> values) {
        return new ArrayList<>(new TreeSet<>(values));
    }
}
