package com.afrotransact.identity.adapter.in.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import com.afrotransact.identity.application.TokenClaims;
import com.afrotransact.identity.application.TokenClaimsService;
import com.afrotransact.identity.domain.PrincipalType;
import com.afrotransact.identity.domain.UserAccount;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
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
 *
 * <p>{@code org} is the seller service's organisation id ({@code org_…}), compared verbatim by every service's
 * ownership checks. Access tokens also carry the user's profile ({@code given_name}, {@code family_name},
 * {@code email}, {@code email_verified}, {@code phone_number} when present) so front ends and services can prefill
 * forms without a userinfo round trip; ID tokens carry the same claims for the scopes the client was granted.
 */
public class IdentityTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    public static final String PRINCIPAL_TYPE = "pt";
    public static final String ORG = "org";
    public static final String ROLES = "roles";
    public static final String PERMS = "perms";
    public static final String GIVEN_NAME = "given_name";
    public static final String FAMILY_NAME = "family_name";
    public static final String EMAIL = "email";
    public static final String EMAIL_VERIFIED = "email_verified";
    public static final String PHONE_NUMBER = "phone_number";
    /** When the person last authenticated (the sign-in, kept across refreshes); step-up reads it (S291). */
    public static final String AUTH_TIME = "auth_time";

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
        c.access().orgId().ifPresent(org -> claims.claim(ORG, org));
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            claims.claim(PERMS, sorted(c.access().permissions()));
            addAccessTokenProfile(claims, c.user());
            // A Date, like the ID token's auth_time: written as epoch seconds, and (unlike an Instant or a Long) both
            // encodable by the JWT encoder and readable back from the authorization's stored claims on refresh.
            signInTime(context.getPrincipal()).ifPresent(at -> claims.claim(AUTH_TIME, Date.from(at)));
        } else if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
            addProfileClaims(claims, c.user(), context.getAuthorizedScopes());
        }
    }

    /** The latest factor (password, passkey) the sign-in recorded, with its time. */
    private static Optional<Instant> signInTime(Authentication principal) {
        return principal.getAuthorities().stream().filter(FactorGrantedAuthority.class::isInstance)
                .map(a -> ((FactorGrantedAuthority) a).getIssuedAt()).filter(Objects::nonNull).max(Instant::compareTo);
    }

    /** Prefill claims on every user access token, independent of the OIDC scopes requested. */
    private static void addAccessTokenProfile(JwtClaimsSet.Builder claims, UserAccount u) {
        putIfPresent(claims, GIVEN_NAME, u.firstName());
        putIfPresent(claims, FAMILY_NAME, u.lastName());
        putIfPresent(claims, EMAIL, u.email());
        claims.claim(EMAIL_VERIFIED, u.emailVerified());
        putIfPresent(claims, PHONE_NUMBER, u.phone());
    }

    private static void addProfileClaims(JwtClaimsSet.Builder claims, UserAccount u, Collection<String> scopes) {
        if (scopes.contains(OidcScopes.EMAIL)) {
            putIfPresent(claims, EMAIL, u.email());
            claims.claim(EMAIL_VERIFIED, u.emailVerified());
        }
        if (scopes.contains(OidcScopes.PROFILE)) {
            putIfPresent(claims, "name", u.displayName());
            putIfPresent(claims, GIVEN_NAME, u.firstName());
            putIfPresent(claims, FAMILY_NAME, u.lastName());
        }
        if (scopes.contains(OidcScopes.PHONE)) {
            putIfPresent(claims, PHONE_NUMBER, u.phone());
        }
    }

    private static void putIfPresent(JwtClaimsSet.Builder claims, String name, String value) {
        if (value != null && !value.isBlank()) {
            claims.claim(name, value);
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
