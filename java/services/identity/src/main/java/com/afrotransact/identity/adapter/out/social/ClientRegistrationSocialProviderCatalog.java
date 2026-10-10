package com.afrotransact.identity.adapter.out.social;

import java.util.Set;
import java.util.TreeSet;

import com.afrotransact.identity.port.SocialProviderCatalog;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/** Providers with OAuth client credentials in this environment (iterable client registration repository). */
@Component
class ClientRegistrationSocialProviderCatalog implements SocialProviderCatalog {

    private final ClientRegistrationRepository registrations;

    ClientRegistrationSocialProviderCatalog(ClientRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    public Set<String> configuredProviders() {
        Set<String> ids = new TreeSet<>();
        if (registrations instanceof Iterable<?> iterable) {
            for (Object r : iterable) {
                if (r instanceof ClientRegistration reg) {
                    ids.add(reg.getRegistrationId());
                }
            }
        }
        return ids;
    }
}
