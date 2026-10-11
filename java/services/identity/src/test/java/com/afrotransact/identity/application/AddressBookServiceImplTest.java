package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.DefaultAddressBookPolicy;
import com.afrotransact.identity.domain.LibPhoneNumberPolicy;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.identity.port.AddressRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;

class AddressBookServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final AddressRepository addresses = mock(AddressRepository.class);
    private final UUID customer = UUID.randomUUID();
    private Actor caller;

    private AddressBookService service() {
        return new AddressBookServiceImpl(new CurrentUser(() -> caller), addresses, new DefaultAddressBookPolicy(),
                new LibPhoneNumberPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Actor staff(String... permissions) {
        return new Actor(UUID.randomUUID().toString(), PrincipalType.STAFF, Optional.empty(),
                Set.of("platform.customer_support"), Set.of(permissions));
    }

    private PostalAddress address(String line1, boolean isDefault, Instant updated) {
        return new PostalAddress(UUID.randomUUID(), customer, "Home", "Ada Okafor", "+18065550101", line1, null,
                "Lubbock", "TX", "79401", "US", null, null, "Gate 4", true, isDefault, NOW, updated);
    }

    @Test
    void staffWithUsersReadListACustomersAddressesDefaultFirst() {
        PostalAddress other = address("9 Elm St", false, NOW.plusSeconds(60));
        PostalAddress home = address("1 Main St", true, NOW);
        when(addresses.findByUser(customer)).thenReturn(List.of(other, home));
        caller = staff("users:read");

        assertThat(service().listOf(customer)).extracting(PostalAddress::line1).containsExactly("1 Main St",
                "9 Elm St");
    }

    @Test
    void accessControlViewersMayListToo() {
        when(addresses.findByUser(customer)).thenReturn(List.of(address("1 Main St", true, NOW)));
        caller = staff("access:read");

        assertThat(service().listOf(customer)).hasSize(1);
    }

    @Test
    void othersAreRefused() {
        caller = staff("orders:read");

        assertThatThrownBy(() -> service().listOf(customer)).isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("users:read");
    }
}
