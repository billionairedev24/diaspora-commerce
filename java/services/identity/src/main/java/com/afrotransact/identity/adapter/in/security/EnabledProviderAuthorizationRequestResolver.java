package com.afrotransact.identity.adapter.in.security;

import com.afrotransact.identity.application.IdentityProviderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/** Starts social sign-in only for providers the platform has enabled (Developers › Identity providers). */
public class EnabledProviderAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    public static final String BASE_URI = "/oauth2/authorization";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final IdentityProviderService providers;

    public EnabledProviderAuthorizationRequestResolver(ClientRegistrationRepository registrations,
                                                       IdentityProviderService providers) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE_URI);
        this.providers = providers;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest resolved = delegate.resolve(request);
        return allowed(resolved) ? resolved : null;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest resolved = delegate.resolve(request, clientRegistrationId);
        return allowed(resolved) ? resolved : null;
    }

    private boolean allowed(OAuth2AuthorizationRequest request) {
        if (request == null) {
            return false;
        }
        Object id = request.getAttributes().get(OAuth2ParameterNames.REGISTRATION_ID);
        return id != null && providers.isEnabled(id.toString());
    }
}
