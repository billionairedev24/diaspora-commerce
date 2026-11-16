package com.afrotransact.orders.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.identity.api.AddressBookApi;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.AddressBook;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.security.EndUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Orders reads the signed-in customer's address book through identity's {@link AddressBookApi} for the person of the request (never
 * for a service, never from the bearer string), and fails like the HTTP adapter does.
 */
class InProcessAddressBookTest {

    private final List<EndUserActor> actors = new ArrayList<>();
    private final List<String> callers = new ArrayList<>();
    private ModuleApiException failure;

    private final AddressBookApi api = user -> {
        actors.add(user);
        callers.add(InProcessCalls.require().clientId());
        if (failure != null) {
            throw failure;
        }
        return List.of(
                new AddressBookApi.Address("addr-1", "Home", "Ada Obi", "+15125550100", "1 Main St", null, "Houston", "TX",
                        "77002", "US", 29.76, -95.37, "Ring twice", true, true),
                new AddressBookApi.Address("addr-2", "Work", "Ada Obi", "+15125550101", "2 Side St", "Suite 4", "Austin", "TX",
                        "78701", null, null, null, null, false, false));
    };

    private final AddressBook book = InProcessWiring.addressBook(api, new EndUsers(true));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(Map<String, Object> claims) {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    void readsTheAddressOfTheSignedInCustomerAsOrdersCallerForThatPerson() {
        signIn(Map.of("sub", "customer-1", "pt", "CUSTOMER", "perms", List.of("mcp:shopper")));
        Optional<ShipTo> found = book.find("ignored-bearer-string", "addr-1");
        assertThat(found).contains(new ShipTo("addr-1", "Ada Obi", "+15125550100", "1 Main St", null, "Houston", "TX", "77002",
                "US", "Ring twice", 29.76, -95.37));
        assertThat(actors).singleElement().satisfies(a -> {
            assertThat(a.userId()).isEqualTo("customer-1");
            assertThat(a.principalType()).isEqualTo("CUSTOMER");
        });
        assertThat(callers).containsExactly("orders-service");
    }

    @Test
    void aMissingCountryIsUsAndAnUnknownIdIsNotFoundAsOverHttp() {
        signIn(Map.of("sub", "customer-1", "pt", "CUSTOMER"));
        assertThat(book.find("t", "addr-2")).get().extracting(ShipTo::country, ShipTo::line2).containsExactly("US", "Suite 4");
        assertThat(book.find("t", "someone-elses-address")).isEmpty();
    }

    @Test
    void aSellerMemberChecksOutAsThemselvesWithoutTheirOrganisation() {
        signIn(Map.of("sub", "customer-1", "pt", "SELLER_MEMBER", "org", "org_malo", "roles",
                List.of("account.customer", "seller.owner"), "perms", List.of("catalog:write")));
        book.find("t", "addr-1");
        assertThat(actors).singleElement().satisfies(a -> {
            assertThat(a.principalType()).isEqualTo("CUSTOMER");
            assertThat(a.organisationId()).isEmpty();
            assertThat(a.roles()).containsExactly("account.customer");
        });
    }

    @Test
    void noSignedInPersonIsTheHttp401AndNothingIsCalled() {
        assertThatThrownBy(() -> book.find("a-bearer-string-is-not-an-identity", "addr-1")).isInstanceOfSatisfying(
                UpstreamFailure.class, f -> {
                    assertThat(f.service()).isEqualTo("identity");
                    assertThat(f.status()).isEqualTo(401);
                    assertThat(f.retryable()).isFalse();
                });
        assertThat(actors).isEmpty();
    }

    @Test
    void aServiceTokenIsRefusedAndNeverBecomesAPerson() {
        signIn(Map.of("sub", "orders-service", "pt", "SERVICE", "scope", "inventory:reserve"));
        assertThatThrownBy(() -> book.find("t", "addr-1")).isInstanceOfSatisfying(UpstreamFailure.class,
                f -> assertThat(f.status()).isEqualTo(403));
        assertThat(actors).isEmpty();
    }

    @Test
    void aRefusalOfTheCalleeKeepsItsStatusAndCode() {
        signIn(Map.of("sub", "customer-1", "pt", "CUSTOMER"));
        failure = new ModuleApiException(403, "auth.missing-permission", "Missing permission identity:addresses.read");
        assertThatThrownBy(() -> book.find("t", "addr-1")).isInstanceOfSatisfying(UpstreamFailure.class, f -> {
            assertThat(f.status()).isEqualTo(403);
            assertThat(f.code()).isEqualTo("auth.missing-permission");
        });
    }
}
