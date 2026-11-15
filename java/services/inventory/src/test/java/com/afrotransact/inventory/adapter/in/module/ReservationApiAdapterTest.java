package com.afrotransact.inventory.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.afrotransact.inventory.api.NodeAddressApi;
import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.inventory.application.CommitResult;
import com.afrotransact.inventory.application.NodeService;
import com.afrotransact.inventory.application.ReservationResult;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.inventory.domain.InsufficientStockException;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationStatus;
import com.afrotransact.inventory.domain.Shortfall;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.DefaultPermissionChecker;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * S6 for inventory's published APIs: the actor inside an in-process call is the caller (SERVICE, its scopes), the use case's
 * permission check refuses a caller without the scope, and the refusal leaves as status 403 like the HTTP endpoint's.
 * The services here check the permission through the real {@link DefaultPermissionChecker}, as the application services do.
 */
class ReservationApiAdapterTest {

    private static final CallerPrincipal ORDERS = new CallerPrincipal("orders", "orders-service",
            Set.of("inventory:reserve", "inventory:read"));
    private static final CallerPrincipal NO_SCOPE = new CallerPrincipal("orders", "orders-service", Set.of());

    private static final ActorResolver ACTORS = () -> {
        CallerPrincipal caller = InProcessCalls.require();
        return new Actor(caller.clientId(), PrincipalType.SERVICE, Optional.empty(), Set.of(), caller.scopes());
    };
    private static final PermissionChecker CHECKER = new DefaultPermissionChecker(ACTORS);

    private static Reservation reservation() {
        return Reservation.hold(UUID.randomUUID(), "o1", "k", Instant.now(), Instant.now().plusSeconds(60));
    }

    /** A reservation service whose every method starts with the permission the real one requires. */
    private static final ReservationService SERVICE = new ReservationService() {
        @Override
        public ReservationResult reserve(ReserveCommand c) {
            CHECKER.require("inventory:reserve");
            if (c.lines().size() > 1) {
                throw new InsufficientStockException(List.of(new Shortfall("SKU1", null, 5, 1)));
            }
            return new ReservationResult(reservation(), false);
        }

        @Override
        public Reservation get(String orderId) {
            // reservationReader(): a SERVICE holding inventory:reserve, otherwise staff with inventory:read
            if (!ACTORS.current().can("inventory:reserve")) {
                CHECKER.require("inventory:read");
            }
            return reservation();
        }

        @Override
        public Reservation confirm(String orderId) {
            CHECKER.require("inventory:reserve");
            return reservation();
        }

        @Override
        public CommitResult commit(String orderId) {
            CHECKER.require("inventory:reserve");
            return null;
        }

        @Override
        public CommitResult commitGroups(String orderId, Set<String> groups) {
            CHECKER.require("inventory:reserve");
            return null;
        }

        @Override
        public Reservation releaseGroups(String orderId, Set<String> groups, String reason) {
            CHECKER.require("inventory:reserve");
            return reservation();
        }

        @Override
        public Reservation release(String orderId, String reason) {
            CHECKER.require("inventory:reserve");
            return reservation();
        }

        @Override
        public int expireDue(int limit) {
            return 0;
        }
    };

    private static final NodeService NODES = mock(NodeService.class, invocation -> {
        CHECKER.require("inventory:read");
        return new PostalAddress(null, "1 Main St", null, "Newark", "NJ", "07101", "US", null);
    });

    private final ReservationApi reservations = new ReservationApiAdapter(SERVICE, ACTORS);
    private final NodeAddressApi addresses = new NodeAddressApiAdapter(NODES, ACTORS);

    static Stream<Arguments> everyMethod() {
        ReservationApi r = new ReservationApiAdapter(SERVICE, ACTORS);
        NodeAddressApi n = new NodeAddressApiAdapter(NODES, ACTORS);
        return Stream.of(
                arg("reserve", () -> r.reserve("o1", "o1", List.of(new ReservationApi.Line("SKU1", null, 1, "l1")))),
                arg("find", () -> r.find("o1")),
                arg("confirm", () -> r.confirm("o1")),
                arg("commit", () -> r.commit("o1")),
                arg("release", () -> r.release("o1", "why")),
                arg("commitGroups", () -> r.commitGroups("o1", Set.of("l1"))),
                arg("releaseGroups", () -> r.releaseGroups("o1", Set.of("l1"), "why")),
                arg("addressOf", () -> n.addressOf("FC1")));
    }

    private static Arguments arg(String name, Runnable call) {
        return Arguments.of(name, call);
    }

    @ParameterizedTest(name = "{0} without the scope is 403")
    @MethodSource("everyMethod")
    void everyMethodRefusesACallerWithoutTheScope(String method, Runnable call) {
        assertThatThrownBy(() -> InProcessCalls.as(NO_SCOPE, () -> {
            call.run();
            return null;
        })).isInstanceOfSatisfying(ModuleApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(403);
            assertThat(ex.code()).isEqualTo("auth.missing-permission");
        });
    }

    @ParameterizedTest(name = "{0} with the scope answers")
    @MethodSource("everyMethod")
    void everyMethodAnswersACallerWithTheScope(String method, Runnable call) {
        InProcessCalls.as(ORDERS, () -> {
            call.run();
            return null;
        });
    }

    @Test
    void refusesToRunWithoutACaller() {
        assertThatThrownBy(() -> reservations.confirm("o1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a caller principal");
    }

    @Test
    void shortStockIsA409WithOneDetailLinePerSku() {
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, () -> reservations.reserve("o1", "o1",
                List.of(new ReservationApi.Line("SKU1", null, 5, null), new ReservationApi.Line("SKU2", null, 1, null)))))
                .isInstanceOfSatisfying(ModuleApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.code()).isEqualTo("inventory.insufficient-stock");
                    assertThat(ex.details()).containsExactly("SKU1: requested 5, available 1");
                });
    }

    @Test
    void addressAnswersForTheCaller() {
        assertThat(InProcessCalls.as(ORDERS, () -> addresses.addressOf("FC1"))).get()
                .extracting(NodeAddressApi.NodeAddress::postalCode).isEqualTo("07101");
        assertThat(ReservationStatus.HELD).isNotNull();
    }
}
