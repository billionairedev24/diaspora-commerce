package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.afrotransact.inventory.application.CommitResult;
import com.afrotransact.inventory.application.InventoryEvents;
import com.afrotransact.inventory.application.LotService;
import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.inventory.application.ReservationResult;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.inventory.domain.InsufficientStockException;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.ReconciliationReport;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationStatus;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Concurrency and lifecycle of reservations against real PostgreSQL (S063 / S064 / S065). */
class ReservationConcurrencyIT extends InventoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ReservationService reservations;
    private LotService lots;
    private int port;

    @BeforeEach
    void wire(@Autowired ReservationService reservationService, @Autowired LotService lotService,
              @LocalServerPort int serverPort) {
        this.reservations = reservationService;
        this.lots = lotService;
        this.port = serverPort;
    }

    /**
     * Flash sale: N = 20 units, N + 25 = 45 simultaneous checkouts over HTTP (one virtual thread each, released
     * together by a latch). Exactly 20 reserve, 25 get 409 inventory.insufficient-stock, and positions, lots and
     * ledger reconcile with zero drift.
     */
    @Test
    void flashSaleNeverOversells() throws Exception {
        int units = 20;
        int buyers = units + 25;
        String sku = sku("FLASH");
        stock(sku, units);

        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < buyers; i++) {
                String body = JSON.writeValueAsString(Map.of("orderId", "ORD-" + sku + "-" + i,
                        "lines", List.of(Map.of("sku", sku, "nodeId", NODE, "qty", 1))));
                calls.add(pool.submit(() -> {
                    start.await();
                    return post("/v1/reservations", body, UUID.randomUUID().toString());
                }));
            }
            start.countDown();
        }
        int created = 0;
        int insufficient = 0;
        for (Future<HttpResponse<String>> call : calls) {
            HttpResponse<String> response = call.get();
            if (response.statusCode() == 201) {
                created++;
            } else {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                        ct -> assertThat(ct).startsWith("application/problem+json"));
                JsonNode problem = JSON.readTree(response.body());
                assertThat(problem.path("code").asString()).isEqualTo("inventory.insufficient-stock");
                assertThat(problem.path("shortfalls").get(0).path("sku").asString()).isEqualTo(sku);
                assertThat(problem.path("shortfalls").get(0).path("requested").asInt()).isEqualTo(1);
                assertThat(problem.path("shortfalls").get(0).path("available").asInt()).isZero();
                insufficient++;
            }
        }

        assertThat(created).isEqualTo(units);
        assertThat(insufficient).isEqualTo(25);
        assertThat(position(sku, "on_hand")).isEqualTo(units);
        assertThat(position(sku, "reserved")).isEqualTo(units);
        assertThat(position(sku, "on_hand - reserved")).isZero();
        assertThat(position(sku, "version")).isEqualTo(1 + units);
        assertThat(ledgerRows(sku, "RESERVE")).isEqualTo(units);
        assertThat(jdbc.sql("select count(*) from reservation where order_id like :p and status = 'HELD'")
                .param("p", "ORD-" + sku + "-%").query(Long.class).single()).isEqualTo(units);
        assertThat(jdbc.sql("select sum(qty_reserved) from lot where sku = :sku").param("sku", sku)
                .query(Integer.class).single()).isEqualTo(units);
        assertThat(events(InventoryEvents.AVAILABILITY_CHANGED, sku)).isEqualTo(1 + units);
        ReconciliationReport report = reconcile();
        assertThat(report.driftUnits()).as(report.drifts().toString()).isZero();
    }

    @Test
    void allLinesReserveOrNone() {
        String rice = sku("RICE");
        String oil = sku("OIL");
        stock(rice, 5);
        stock(oil, 1);
        String orderId = "ORD-" + UUID.randomUUID();

        assertThatThrownBy(() -> reservations.reserve(new ReserveCommand(orderId, "k1", List.of(
                new ReserveCommand.Line(rice, NODE, 3), new ReserveCommand.Line(oil, NODE, 2)))))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> assertThat(ex.shortfalls())
                        .singleElement().satisfies(s -> {
                            assertThat(s.sku()).isEqualTo(oil);
                            assertThat(s.requested()).isEqualTo(2);
                            assertThat(s.available()).isEqualTo(1);
                        }));

        assertThat(position(rice, "reserved")).isZero();
        assertThat(position(oil, "reserved")).isZero();
        assertThat(ledgerRows(rice, "RESERVE")).isZero();
        assertThat(jdbc.sql("select count(*) from reservation where order_id = :o").param("o", orderId)
                .query(Long.class).single()).isZero();

        ReservationResult ok = reservations.reserve(new ReserveCommand(orderId, "k2", List.of(
                new ReserveCommand.Line(rice, NODE, 3), new ReserveCommand.Line(oil, null, 1))));
        assertThat(ok.replayed()).isFalse();
        assertThat(ok.reservation().lines()).extracting(l -> l.nodeId()).containsOnly(NODE);
        assertThat(position(rice, "reserved")).isEqualTo(3);
        assertThat(position(oil, "reserved")).isEqualTo(1);
    }

    @Test
    void retryWithSameOrderIdNeverDoubleReserves() throws Exception {
        String sku = sku("RETRY");
        stock(sku, 10);
        String orderId = "ORD-" + UUID.randomUUID();
        ReserveCommand cmd = new ReserveCommand(orderId, "key-1", List.of(new ReserveCommand.Line(sku, NODE, 4)));

        ReservationResult first = reservations.reserve(cmd);
        ReservationResult again = reservations.reserve(new ReserveCommand(orderId, "key-2", cmd.lines()));
        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.reservation().id()).isEqualTo(first.reservation().id());
        assertThat(position(sku, "reserved")).isEqualTo(4);

        // the same order id fired concurrently: one reservation, everyone else gets the replay
        String racedOrder = "ORD-" + UUID.randomUUID();
        String body = JSON.writeValueAsString(Map.of("orderId", racedOrder,
                "lines", List.of(Map.of("sku", sku, "nodeId", NODE, "qty", 2))));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return post("/v1/reservations", body, UUID.randomUUID().toString());
                }));
            }
            start.countDown();
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<HttpResponse<String>> call : calls) {
            statuses.add(call.get().statusCode());
        }
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(9);
        assertThat(position(sku, "reserved")).isEqualTo(6);
        assertThat(ledgerRows(sku, "RESERVE")).isEqualTo(2);

        assertThatThrownBy(() -> reservations.reserve(new ReserveCommand(orderId, "key-3",
                List.of(new ReserveCommand.Line(sku, NODE, 5)))))
                .hasMessageContaining("different lines");
    }

    @Test
    void expiredHoldsAreReleasedBySweep() {
        String sku = sku("TTL");
        stock(sku, 3);
        String orderId = "ORD-" + UUID.randomUUID();
        Reservation held = reservations.reserve(new ReserveCommand(orderId, "k",
                List.of(new ReserveCommand.Line(sku, NODE, 2)))).reservation();
        assertThat(held.expiresAt()).isAfter(held.createdAt().plusSeconds(14 * 60));
        assertThat(position(sku, "reserved")).isEqualTo(2);

        jdbc.sql("update reservation set expires_at = now() - interval '1 minute' where order_id = :o")
                .param("o", orderId).update();
        assertThat(reservations.expireDue(500)).isGreaterThanOrEqualTo(1);

        assertThat(reservations.get(orderId).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(position(sku, "reserved")).isZero();
        assertThat(ledgerRows(sku, "RELEASE")).isEqualTo(1);
        assertThat(events(InventoryEvents.RESERVATION_RELEASED, orderId)).isEqualTo(1);
        assertThat(reservations.release(orderId, "late cancel").status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservations.expireDue(500)).isZero();
    }

    @Test
    void confirmedHoldsSurviveTheSweepAndReleaseIsIdempotent() {
        String sku = sku("CONF");
        stock(sku, 5);
        String paid = "ORD-" + UUID.randomUUID();
        String cancelled = "ORD-" + UUID.randomUUID();
        reservations.reserve(new ReserveCommand(paid, "k", List.of(new ReserveCommand.Line(sku, NODE, 2))));
        reservations.reserve(new ReserveCommand(cancelled, "k", List.of(new ReserveCommand.Line(sku, NODE, 1))));

        assertThat(reservations.confirm(paid).expiresAt()).isNull();
        jdbc.sql("update reservation set created_at = now() - interval '1 hour' where order_id = :o")
                .param("o", paid).update();
        reservations.expireDue(500);
        assertThat(reservations.get(paid).status()).isEqualTo(ReservationStatus.HELD);

        assertThat(reservations.release(cancelled, "payment failed").status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.release(cancelled, "payment failed").status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(position(sku, "reserved")).isEqualTo(2);
        assertThat(ledgerRows(sku, "RELEASE")).isEqualTo(1);
    }

    /** FEFO: earliest-expiring eligible lot first, lots expiring within 7 days skipped; commit posts COGS by lot. */
    @Test
    void allocatesLotsFefoAndCommitsCogsAtLandedCost() {
        String sku = sku("FEFO");
        receive(NODE, line(sku, "L-LATE", 700, 5, 0, 400), line(sku, "L-EARLY", 400, 3, 0, 300));
        // a short-dated lot is quarantined on receipt; staff release it, but FEFO still skips it (< 7 days left)
        Lot soon = receive(NODE, new ReceiveCommand.Line(sku, "L-SOON",
                today().minusDays(360), today().plusDays(5), 9, 0, "A-02-01", 100, "USD", 365)).lots().getFirst();
        assertThat(soon.status()).isEqualTo(LotStatus.QUARANTINE);
        assertThat(lots.release(soon.id(), "Inspected: sell in staff shop only").status()).isEqualTo(LotStatus.SELLABLE);

        String orderId = "ORD-" + UUID.randomUUID();
        Reservation r = reservations.reserve(new ReserveCommand(orderId, "k",
                List.of(new ReserveCommand.Line(sku, NODE, 4)))).reservation();

        assertThat(r.lines().getFirst().allocations()).extracting(LotAllocation::lotNo)
                .containsExactly("L-EARLY", "L-LATE");
        assertThat(r.lines().getFirst().allocations()).extracting(LotAllocation::qty).containsExactly(3, 1);

        CommitResult shipped = reservations.commit(orderId);
        assertThat(shipped.cogs()).containsExactly(new CommitResult.Money("USD", 3 * 300 + 400));
        assertThat(shipped.reservation().status()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(reservations.commit(orderId).replayed()).isTrue();
        assertThat(position(sku, "on_hand")).isEqualTo(5 + 3 + 9 - 4);
        assertThat(position(sku, "reserved")).isZero();
        assertThat(ledgerRows(sku, "SHIP")).isEqualTo(2);
        assertThat(events(InventoryEvents.RESERVATION_COMMITTED, orderId)).isEqualTo(1);
        assertThatThrownBy(() -> reservations.release(orderId, "too late"))
                .hasMessageContaining("COMMITTED");
        assertThat(reconcile().driftUnits()).isZero();
    }

    @Test
    void onlyServicePrincipalsReserve() {
        String sku = sku("AUTH");
        stock(sku, 2);
        TestActors.as(TestActors.staff("inventory:read", "inventory:reserve"));
        assertThatThrownBy(() -> reservations.reserve(new ReserveCommand("ORD-" + UUID.randomUUID(), "k",
                List.of(new ReserveCommand.Line(sku, NODE, 1)))))
                .isInstanceOfSatisfying(ForbiddenActionException.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.service-only"));
        TestActors.as(TestActors.ORDERS_SERVICE);
        assertThat(reservations.reserve(new ReserveCommand("ORD-" + UUID.randomUUID(), "k",
                List.of(new ReserveCommand.Line(sku, NODE, 1)))).replayed()).isFalse();
    }

    private HttpResponse<String> post(String path, String json, String idempotencyKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
