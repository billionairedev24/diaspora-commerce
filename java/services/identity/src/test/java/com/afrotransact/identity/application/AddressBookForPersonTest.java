package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.api.AddressBookApi;
import com.afrotransact.identity.domain.DefaultAddressBookPolicy;
import com.afrotransact.identity.domain.LibPhoneNumberPolicy;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.identity.port.AddressRepository;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/**
 * Identity's real address-book use case entered for a person through {@code ModuleEntry.runAsUser} (what the published
 * {@code AddressBookApi} does): the owner is the token's subject and nothing else, so another customer's addresses cannot be reached,
 * the calling module needs the delegation scope, and a person is never a service.
 */
class AddressBookForPersonTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final CallerPrincipal ORDERS = InProcessKit.caller("orders", AddressBookApi.CALLER_SCOPE);

    private final AddressRepository addresses = mock(AddressRepository.class);
    private final UUID ada = UUID.randomUUID();
    private final UUID bola = UUID.randomUUID();
    private final ModuleEntry entry = new ModuleEntry(InProcessKit.ACTORS);
    private final AddressBookService book = new AddressBookServiceImpl(new CurrentUser(InProcessKit.ACTORS), addresses,
            new DefaultAddressBookPolicy(), new LibPhoneNumberPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));

    private PostalAddress address(UUID owner, String line1) {
        return new PostalAddress(UUID.randomUUID(), owner, "Home", "Name", "+18065550101", line1, null, "Lubbock", "TX",
                "79401", "US", null, null, null, true, true, NOW, NOW);
    }

    private List<String> read(CallerPrincipal caller, EndUserActor person) {
        return InProcessCalls.as(caller, "identity", () -> entry.runAsUser(person, AddressBookApi.CALLER_SCOPE,
                () -> book.list().stream().map(PostalAddress::line1).toList()));
    }

    private EndUserActor person(UUID id, Object... claims) {
        Object[] all = new Object[claims.length + 2];
        all[0] = "pt";
        all[1] = "CUSTOMER";
        System.arraycopy(claims, 0, all, 2, claims.length);
        return InProcessKit.user(id.toString(), all);
    }

    @Test
    void aCustomerGetsTheirOwnAddressesAndOnlyThose() {
        when(addresses.findByUser(ada)).thenReturn(List.of(address(ada, "1 Ada St")));
        when(addresses.findByUser(bola)).thenReturn(List.of(address(bola, "2 Bola St")));
        assertThat(read(ORDERS, person(ada))).containsExactly("1 Ada St");
        assertThat(read(ORDERS, person(bola))).containsExactly("2 Bola St");
    }

    @Test
    void anotherCustomersIdIsNeverReadWhateverTheCallerSays() {
        when(addresses.findByUser(ada)).thenReturn(List.of(address(ada, "1 Ada St")));
        when(addresses.findByUser(bola)).thenReturn(List.of(address(bola, "2 Bola St")));
        // there is no parameter to name another customer: Bola's person reads Bola's book even when Ada is the one being served
        assertThat(read(ORDERS, person(bola))).doesNotContain("1 Ada St");
        // and the staff-only listing of someone else's book is refused for a customer person (no users:read in their token)
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "identity", () -> entry.runAsUser(person(bola),
                AddressBookApi.CALLER_SCOPE, () -> book.listOf(ada)))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.missing-permission");
                });
    }

    @Test
    void aCallerWithoutTheDelegationScopeIsRefused() {
        assertThatThrownBy(() -> read(InProcessKit.caller("orders", "users:read", "inventory:reserve"), person(ada)))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.missing-permission");
                });
    }

    @Test
    void theCallersScopesNeverBecomeThePersonsPermissions() {
        // a module holding users:read can read an address book as a service only through its own API, never by acting as a person
        CallerPrincipal rich = InProcessKit.caller("orders", AddressBookApi.CALLER_SCOPE, "users:read");
        assertThatThrownBy(() -> InProcessCalls.as(rich, "identity", () -> entry.runAsUser(person(bola),
                AddressBookApi.CALLER_SCOPE, () -> book.listOf(ada)))).isInstanceOfSatisfying(ModuleApiException.class,
                        e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void anOrganisationMemberReadsTheirOwnBookAsThemselvesAndAnotherOrgIsRefusedWhereOrgsMatter() {
        EndUserActor member = person(ada, "org", "org_malo", "roles", List.of("account.customer", "seller.owner"));
        when(addresses.findByUser(ada)).thenReturn(List.of(address(ada, "1 Ada St")));
        assertThat(read(ORDERS, member.asCustomer())).containsExactly("1 Ada St");
        member.requireOrganisation("org_malo");
        assertThatThrownBy(() -> member.requireOrganisation("org_other")).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.code()).isEqualTo("auth.org-mismatch"));
    }

    @Test
    void aTokenThatIsNotAUserAccountIsRefusedLikeTheHttpEndpoint() {
        EndUserActor notAnAccount = InProcessKit.user("not-a-uuid", "pt", "CUSTOMER");
        assertThatThrownBy(() -> read(ORDERS, notAnAccount)).isInstanceOfSatisfying(RuntimeException.class,
                e -> assertThat(e.getMessage()).contains("user access token"));
    }
}
