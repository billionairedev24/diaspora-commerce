package com.afrotransact.identity.adapter.in.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.application.FederatedLoginService;
import com.afrotransact.identity.application.FederatedProfile;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.platform.web.error.DomainException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Google / Apple (OIDC): loads the provider's verified claims, then resolves or creates the AfroTransact account.
 * The returned user's name is the account id so tokens carry the same {@code sub} as password sign-ins.
 */
public class FederatedOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final FederatedLoginService federated;

    public FederatedOidcUserService(FederatedLoginService federated) {
        this.federated = federated;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser provider = delegate.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();
        Boolean verified = provider.getEmailVerified();
        FederatedProfile profile = new FederatedProfile(registrationId, provider.getSubject(), provider.getEmail(),
                Boolean.TRUE.equals(verified), provider.getGivenName(), provider.getFamilyName());
        UUID accountId;
        try {
            accountId = federated.signIn(profile, FederatedPrincipals.signedInUser());
        } catch (DomainException | IdentityRuleViolation ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied", ex.getMessage(), null), ex);
        }
        Map<String, Object> claims = new HashMap<>(provider.getUserInfo() == null ? Map.of()
                : provider.getUserInfo().getClaims());
        claims.put("sub", provider.getSubject());
        claims.put(FederatedPrincipals.ACCOUNT_ID, accountId.toString());
        return new DefaultOidcUser(provider.getAuthorities(), provider.getIdToken(), new OidcUserInfo(claims),
                FederatedPrincipals.ACCOUNT_ID);
    }
}
