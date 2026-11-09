package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.platform.security.ActingAsActorResolver;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Every signed-in person shops as themselves: with {@code X-Acting-As: CUSTOMER} (storefront, customer app) a
 * customer, seller or provider member, driver or staff / platform admin is a CUSTOMER of their own subject for
 * checkout, cart and their own orders, and never sees someone else's order.
 */
class OrdersAccessActingAsTest {

    private static final PermissionChecker PERMISSIONS = mock(PermissionChecker.class);
    private static final ResourceAccessChecker RELATIONSHIPS = mock(ResourceAccessChecker.class);

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    static Stream<Actor> people() {
        return Stream.of(
                new Actor("p1", PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer"), Set.of()),
                new Actor("p1", PrincipalType.SELLER_MEMBER, Optional.of("org_s"),
                        Set.of("account.customer", "seller.owner"), Set.of("orders:read", "catalog:write")),
                new Actor("p1", PrincipalType.PROVIDER_MEMBER, Optional.of("org_p"),
                        Set.of("account.customer", "provider.owner"), Set.of("bookings:manage")),
                new Actor("p1", PrincipalType.DRIVER, Optional.empty(), Set.of("platform.driver"),
                        Set.of("delivery:operate")),
                new Actor("p1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.operations_manager"),
                        Set.of("orders:read", "orders:manage")),
                new Actor("p1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.super_admin"), Set.of("*")));
    }

    private static OrdersAccess access(Actor delegate, String actingAs) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout");
        if (actingAs != null) {
            request.addHeader(ActingAsActorResolver.HEADER, actingAs);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return new OrdersAccess(PERMISSIONS, RELATIONSHIPS, new ActingAsActorResolver(() -> delegate));
    }

    private static Order orderOf(String customerId) {
        Order order = mock(Order.class);
        when(order.customerId()).thenReturn(customerId);
        when(order.number()).thenReturn("AT-1");
        return order;
    }

    @ParameterizedTest
    @MethodSource("people")
    void everyPersonChecksOutAsTheirOwnCustomerId(Actor person) {
        Actor shopper = access(person, "CUSTOMER").customer();

        assertThat(shopper.type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(shopper.subject()).isEqualTo("p1");
        assertThat(shopper.orgId()).isEmpty();
        assertThat(shopper.can("orders:manage")).isFalse();
        if (person.type() == PrincipalType.STAFF) {
            assertThat(shopper.permissions()).isEmpty();
        }
        assertThat(shopper.can("*")).isFalse();
    }

    @ParameterizedTest
    @MethodSource("people")
    void everyPersonSeesOnlyTheirOwnOrdersWhileShopping(Actor person) {
        OrdersAccess access = access(person, "CUSTOMER");

        assertThat(access.viewerOf(orderOf("p1")).kind()).isEqualTo(Viewer.Kind.CUSTOMER);
        assertThatThrownBy(() -> access.viewerOf(orderOf("someone-else"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void staffWithoutTheShopperContextKeepTheirStaffViewAndCannotCheckOut() {
        Actor staff = people().filter(a -> a.type() == PrincipalType.STAFF).findFirst().orElseThrow();

        assertThatThrownBy(() -> access(staff, null).customer()).isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("shoppers");
    }

    @Test
    void servicesPartnerAppsAndAgentsStayNonShoppersEvenWithTheHeader() {
        for (PrincipalType type : Set.of(PrincipalType.SERVICE, PrincipalType.PARTNER_APP, PrincipalType.AI_AGENT)) {
            Actor caller = new Actor("x", type, Optional.empty(), Set.of(), Set.of("orders:read"));
            assertThatThrownBy(() -> access(caller, "CUSTOMER").customer())
                    .isInstanceOf(ForbiddenActionException.class);
        }
    }
}
