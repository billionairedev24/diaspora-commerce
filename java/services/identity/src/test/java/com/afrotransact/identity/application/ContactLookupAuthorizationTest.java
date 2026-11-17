package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.port.AddressRepository;
import com.afrotransact.identity.port.NotificationPreferencesRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.PhoneVerificationRepository;
import com.afrotransact.identity.port.PushDeviceRepository;
import com.afrotransact.identity.port.ServiceTokenVerifier;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.LocalActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/**
 * The internal contact lookup (GET /v1/internal/users/{id}/contact) accepts exactly a service (client-credentials) token
 * with users:read, with security on (resource-server actor) and off (core local: LocalActorResolver answers the STAFF
 * super admin, the verified bearer token is the caller). An unknown user id answers 404 once authorised, so a
 * NotFoundException here means "let through".
 */
class ContactLookupAuthorizationTest {

    private static final UUID USER = UUID.randomUUID();

    private static ContactLookupService lookups(ActorResolver actors, ServiceTokenVerifier verifier) {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.findById(USER)).thenReturn(Optional.empty());
        return new ContactLookupServiceImpl(new CurrentUser(actors, verifier), users,
                mock(NotificationPreferencesRepository.class), mock(PushDeviceRepository.class),
                mock(PhoneVerificationRepository.class), mock(AddressRepository.class), mock(OrganizationRepository.class),
                mock(UserAccessRepository.class));
    }

    private static Actor actor(PrincipalType type, Set<String> perms) {
        return new Actor("subject", type, Optional.empty(), Set.of(), perms);
    }

    private static void assertAllowed(ContactLookupService service) {
        assertThatThrownBy(() -> service.contact(USER, null)).isInstanceOf(NotFoundException.class);
    }

    private static void assertForbidden(ContactLookupService service) {
        assertThatThrownBy(() -> service.contact(USER, null)).isInstanceOfSatisfying(ForbiddenActionException.class,
                e -> assertThat(e.getMessage()).contains("service token with users:read"));
    }

    // security on: the resource-server chain verified the token, the actor resolver is authoritative

    @Test
    void securityOnAcceptsAServiceActorWithUsersRead() {
        assertAllowed(lookups(() -> actor(PrincipalType.SERVICE, Set.of("users:read")), null));
    }

    @Test
    void securityOnRejectsAServiceWithoutUsersReadAndEveryUserType() {
        assertForbidden(lookups(() -> actor(PrincipalType.SERVICE, Set.of("orders:lookup")), null));
        for (PrincipalType type : List.of(PrincipalType.CUSTOMER, PrincipalType.STAFF, PrincipalType.DRIVER,
                PrincipalType.SELLER_MEMBER)) {
            assertForbidden(lookups(() -> actor(type, Set.of("users:read")), null));
        }
    }

    // security off (core local): every call resolves to the STAFF super admin unless the token is a user's

    /** Core local: LocalActorResolver (STAFF super admin) plus the verifier's answer for the request's bearer token. */
    private static ContactLookupService securityOff(Optional<Actor> verified) {
        return lookups(new LocalActorResolver(), () -> verified);
    }

    @Test
    void securityOffAcceptsTheVerifiedClientCredentialsTokenWithUsersRead() {
        assertAllowed(securityOff(Optional.of(actor(PrincipalType.SERVICE, Set.of("users:read", "delivery-runs:audience.read")))));
    }

    @Test
    void securityOffRejectsAServiceTokenWithoutUsersRead() {
        assertForbidden(securityOff(Optional.of(actor(PrincipalType.SERVICE, Set.of("orders:lookup")))));
    }

    @Test
    void securityOffRejectsUserTokensAndNoToken() {
        // no verified service token (a user token, a bad token or none): the local super admin is STAFF, never SERVICE
        assertForbidden(securityOff(Optional.empty()));
    }
}
