package com.afrotransact.identity.adapter.in.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.application.FederatedLoginService;
import com.afrotransact.identity.application.FederatedProfile;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.platform.web.error.DomainException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Plain OAuth 2.0 providers (Facebook). Facebook only returns confirmed email addresses, so a returned email is
 * treated as verified.
 */
public class FederatedOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final FederatedLoginService federated;

    public FederatedOAuth2UserService(FederatedLoginService federated) {
        this.federated = federated;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        OAuth2User provider = delegate.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();
        String subject = String.valueOf(provider.getAttributes().get("id"));
        String email = (String) provider.getAttributes().get("email");
        String first = (String) provider.getAttributes().get("first_name");
        String last = (String) provider.getAttributes().get("last_name");
        UUID accountId;
        try {
            accountId = federated.signIn(new FederatedProfile(registrationId, subject, email, email != null, first,
                    last), FederatedPrincipals.signedInUser());
        } catch (DomainException | IdentityRuleViolation ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied", ex.getMessage(), null), ex);
        }
        Map<String, Object> attributes = new HashMap<>(provider.getAttributes());
        attributes.put(FederatedPrincipals.ACCOUNT_ID, accountId.toString());
        return new DefaultOAuth2User(provider.getAuthorities(), attributes, FederatedPrincipals.ACCOUNT_ID);
    }
}
