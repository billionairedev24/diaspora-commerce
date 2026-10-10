package com.afrotransact.identity.config;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** Social provider registrations from configuration; may be empty (no provider configured in this environment). */
final class ConfiguredClientRegistrationRepository implements ClientRegistrationRepository,
        Iterable<ClientRegistration> {

    private final Map<String, ClientRegistration> byId = new LinkedHashMap<>();

    ConfiguredClientRegistrationRepository(List<ClientRegistration> registrations) {
        registrations.forEach(r -> byId.put(r.getRegistrationId(), r));
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return byId.get(registrationId);
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return List.copyOf(byId.values()).iterator();
    }
}
