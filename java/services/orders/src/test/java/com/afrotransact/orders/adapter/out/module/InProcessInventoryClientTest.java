package com.afrotransact.orders.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Orders reserves stock through inventory's published API as its own principal, and fails like the HTTP client does. */
class InProcessInventoryClientTest {

    private static final CallerPrincipal ORDERS = new CallerPrincipal("orders", "orders-service",
            Set.of("inventory:reserve"));

    private final List<String> calls = new ArrayList<>();
    private ModuleApiException failure;

    private final ReservationApi api = new ReservationApi() {
        @Override
        public ReservationInfo reserve(String orderId, String idempotencyKey, List<Line> lines) {
            calls.add("reserve " + orderId + " key=" + idempotencyKey + " as " + InProcessCalls.require().clientId());
            fail();
            return new ReservationInfo(orderId, "HELD", false, lines.stream()
                    .map(l -> new ReservedLine(l.sku(), "FC1", l.qty(), l.group(), List.of())).toList());
        }

        @Override
        public Optional<ReservationInfo> find(String orderId) {
            return Optional.empty();
        }

        @Override
        public ReservationInfo confirm(String orderId) {
            calls.add("confirm " + orderId);
            return null;
        }

        @Override
        public void commit(String orderId) {
            calls.add("commit " + orderId);
        }

        @Override
        public ReservationInfo release(String orderId, String reason) {
            calls.add("release " + orderId + " " + reason);
            return null;
        }

        @Override
        public void commitGroups(String orderId, Set<String> groups) {
            calls.add("commitGroups " + orderId + " " + groups);
            fail();
        }

        @Override
        public ReservationInfo releaseGroups(String orderId, Set<String> groups, String reason) {
            calls.add("releaseGroups " + orderId + " " + groups);
            return null;
        }

        private void fail() {
            if (failure != null) {
                throw failure;
            }
        }
    };

    private final InventoryClient client = clientOver();

    private InventoryClient clientOver() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("reservationApi", api);
        beans.registerSingleton("callers", (CallerPrincipals) module -> ORDERS);
        beans.registerSingleton("upstream", new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class)));
        return new InProcessInventoryClient(beans.getBeanProvider(ReservationApi.class),
                beans.getBean(ModuleUpstream.class), beans);
    }

    @Test
    void reservesAsOrdersWithTheOrderIdAsKeyAndAnswersTheAllocatedNode() {
        List<InventoryClient.Reserved> reserved = client.reserve("o1", List.of(new InventoryClient.Item("SKU1", 2, "l1")));
        assertThat(calls).containsExactly("reserve o1 key=o1 as orders-service");
        assertThat(reserved).containsExactly(new InventoryClient.Reserved("SKU1", "FC1", 2, "l1"));
    }

    @Test
    void shortStockIsTheNonRetryableUpstreamFailureTheHttpClientRaises() {
        failure = new ModuleApiException(409, "inventory.insufficient-stock", "Insufficient stock",
                List.of("SKU1: requested 5, available 1"), null);
        assertThatThrownBy(() -> client.reserve("o1", List.of(new InventoryClient.Item("SKU1", 5))))
                .isInstanceOfSatisfying(UpstreamFailure.class, f -> {
                    assertThat(f.service()).isEqualTo("inventory");
                    assertThat(f.status()).isEqualTo(409);
                    assertThat(f.code()).isEqualTo("inventory.insufficient-stock");
                    assertThat(f.retryable()).isFalse();
                    assertThat(f.details()).containsExactly("SKU1: requested 5, available 1");
                });
    }

    @Test
    void unknownGroupsAre404SoTheCallerFallsBackToWholeOrderCommit() {
        failure = new ModuleApiException(404, "inventory.group-not-found", "no such group");
        assertThatThrownBy(() -> client.commitGroups("o1", "s1", List.of("l1")))
                .isInstanceOfSatisfying(UpstreamFailure.class, f -> assertThat(f.status()).isEqualTo(404));
    }

    @Test
    void aMissingScopeIs403AndNotRetried() {
        failure = new ModuleApiException(403, "auth.missing-permission", "Missing permission inventory:reserve");
        assertThatThrownBy(() -> client.reserve("o1", List.of(new InventoryClient.Item("SKU1", 1))))
                .isInstanceOfSatisfying(UpstreamFailure.class, f -> {
                    assertThat(f.status()).isEqualTo(403);
                    assertThat(f.retryable()).isFalse();
                });
    }

    @Test
    void anUnexpectedFailureIsRetryableUnavailable() {
        ReservationApi broken = new ReservationApi() {
            @Override
            public ReservationInfo reserve(String o, String k, List<Line> l) {
                throw new IllegalStateException("boom");
            }

            @Override
            public Optional<ReservationInfo> find(String o) {
                return Optional.empty();
            }

            @Override
            public ReservationInfo confirm(String o) {
                return null;
            }

            @Override
            public void commit(String o) {
            }

            @Override
            public ReservationInfo release(String o, String r) {
                return null;
            }

            @Override
            public void commitGroups(String o, Set<String> g) {
            }

            @Override
            public ReservationInfo releaseGroups(String o, Set<String> g, String r) {
                return null;
            }
        };
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("api", broken);
        beans.registerSingleton("callers", (CallerPrincipals) module -> ORDERS);
        InProcessInventoryClient c = new InProcessInventoryClient(beans.getBeanProvider(ReservationApi.class),
                new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class)), beans);
        assertThatThrownBy(() -> c.reserve("o1", List.of())).isInstanceOfSatisfying(UpstreamFailure.class, f -> {
            assertThat(f.code()).isEqualTo("inventory.unavailable");
            assertThat(f.retryable()).isTrue();
        });
    }

    @Test
    void releaseAndConfirmReachInventory() {
        client.confirm("o1");
        client.release("o1", null);
        client.commit("o1");
        client.releaseGroups("o1", "s1", List.of("l1"), "late");
        assertThat(calls).containsExactly("confirm o1", "release o1 ", "commit o1", "releaseGroups o1 [l1]");
    }

    @Test
    void failsAtStartWhenInventoryIsNotInThisJvm() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        assertThatThrownBy(() -> new InProcessInventoryClient(beans.getBeanProvider(ReservationApi.class),
                new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class)), beans))
                .hasMessageContaining("Module 'orders' is configured at.orders.inventory.mode=in-process but module "
                        + "'inventory' is not running in this JVM");
    }
}
