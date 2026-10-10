package com.afrotransact.identity.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * Social sign-in client registrations: Google (OIDC), Apple (OIDC, form_post) and Facebook (OAuth 2.0). A provider is
 * registered only when its client id is configured; whether it appears on sign-in screens is the platform switch
 * in the {@code identity_provider} table.
 */
@Configuration(proxyBeanMethods = false)
class SocialLoginConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SocialLoginConfiguration.class);
    private static final String REDIRECT = "{baseUrl}/login/oauth2/code/{registrationId}";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(IdentityProperties props) {
        List<ClientRegistration> registrations = new ArrayList<>();
        configured(props, "google").ifPresent(s -> registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(s.clientId()).clientSecret(s.clientSecret()).build()));
        configured(props, "facebook").ifPresent(s -> registrations.add(CommonOAuth2Provider.FACEBOOK
                .getBuilder("facebook").clientId(s.clientId()).clientSecret(s.clientSecret())
                .userInfoUri("https://graph.facebook.com/me?fields=id,first_name,last_name,email").build()));
        configured(props, "apple").ifPresent(s -> registrations.add(ClientRegistration.withRegistrationId("apple")
                .clientId(s.clientId()).clientSecret(s.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT)
                .scope("openid", "email", "name")
                .authorizationUri("https://appleid.apple.com/auth/authorize?response_mode=form_post")
                .tokenUri("https://appleid.apple.com/auth/token")
                .jwkSetUri("https://appleid.apple.com/auth/keys")
                .issuerUri("https://appleid.apple.com")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Apple")
                .build()));
        LOG.info("Social sign-in providers configured: {}",
                registrations.stream().map(ClientRegistration::getRegistrationId).toList());
        return new ConfiguredClientRegistrationRepository(registrations);
    }

    private static Optional<IdentityProperties.Social> configured(IdentityProperties props, String id) {
        IdentityProperties.Social s = props.social().get(id);
        return s == null || s.clientId() == null || s.clientId().isBlank() ? Optional.empty()
                : Optional.of(s);
    }
}
