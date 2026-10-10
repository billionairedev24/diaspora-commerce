package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.inventory.application.InventoryEvents;
import com.afrotransact.inventory.application.LotLifecycleService;
import com.afrotransact.inventory.application.LotService;
import com.afrotransact.inventory.application.ReceiptResult;
import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.inventory.application.ReturnCommand;
import com.afrotransact.inventory.application.ReturnService;
import com.afrotransact.inventory.application.StockQueryService;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.InventoryStateConflict;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.domain.ReturnCondition;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Receipts, lot operations, returns, nightly jobs, reconciliation, ledger immutability and access rules. */
class StockOperationsIT extends InventoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private LotService lots;
    private LotLifecycleService lifecycle;
    private ReservationService reservations;
    private ReturnService returns;
    private StockQueryService queries;
    private MeterRegistry meters;
    private int port;

    @BeforeEach
    void wire(@Autowired LotService lotService, @Autowired LotLifecycleService lifecycleService,
              @Autowired ReservationService reservationService, @Autowired ReturnService returnService,
              @Autowired StockQueryService queryService, @Autowired MeterRegistry registry,
              @LocalServerPort int serverPort) {
        this.lots = lotService;
        this.lifecycle = lifecycleService;
        this.reservations = reservationService;
        this.returns = returnService;
        this.queries = queryService;
        this.meters = registry;
        this.port = serverPort;
    }

    @Test
    void receiptCreatesSellableShortDatedAndDamagedLots() {
        String sku = sku("JOL");
        ReceiptResult r = receive(NODE, line(sku, "L2604-JOL-01", 500, 40, 2, 192),
                new ReceiveCommand.Line(sku, "L2511-JOL-09", today().minusDays(400), today().plusDays(120), 6, 0,
                        "A-03-03", 181, "USD", 520));

        assertThat(r.lots()).extracting(Lot::lotNo, Lot::status, Lot::qtyOnHand).containsExactlyInAnyOrder(
                tuple("L2604-JOL-01", LotStatus.SELLABLE, 40),
                tuple("L2604-JOL-01-DMG", LotStatus.QUARANTINE, 2),
                tuple("L2511-JOL-09", LotStatus.QUARANTINE, 6));
        assertThat(r.lots()).filteredOn(l -> l.lotNo().equals("L2511-JOL-09")).singleElement()
                .satisfies(l -> assertThat(l.reason()).contains("Short-dated"));
        assertThat(position(sku, "on_hand")).isEqualTo(40);
        assertThat(position(sku, "damaged")).isEqualTo(8);
        assertThat(ledgerRows(sku, "RECEIVE")).isEqualTo(3);
        assertThat(ledgerRows(sku, "PUT_AWAY")).isEqualTo(3);
        assertThat(ledgerRows(sku, "QUARANTINE")).isEqualTo(2);
        assertThat(events(InventoryEvents.LOT_CREATED, sku)).isEqualTo(3);
        assertThat(events(InventoryEvents.LOT_QUARANTINED, sku)).isEqualTo(2);

        assertThatThrownBy(() -> receive(NODE, line(sku, "L2604-JOL-01", 500, 1, 0, 192)))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.lot-exists"));
    }

    @Test
    void expiredLotsRejectTheWholeReceipt() {
        String sku = sku("EXP");
        assertThatThrownBy(() -> receive(NODE, line(sku, "L-OK", 400, 5, 0, 100),
                new ReceiveCommand.Line(sku, "L-OLD", today().minusDays(400), today().minusDays(1), 5, 0, null, 100,
                        "USD", 365)))
                .isInstanceOfSatisfying(InventoryRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo("inventory.lot-rejected");
                    assertThat(ex.fieldErrors()).containsKey("lines[1].expDate");
                });
        assertThat(jdbc.sql("select count(*) from lot where sku = :sku").param("sku", sku).query(Long.class)
                .single()).isZero();
    }

    @Test
    void receiptsAreIdempotentPerKey() {
        String sku = sku("IDEM");
        ReceiveCommand cmd = new ReceiveCommand("GRN-" + UUID.randomUUID(), NODE, null,
                List.of(line(sku, "L-1", 400, 12, 0, 100)));
        ReceiptResult first = receiving.receive(cmd, "receipt-key-" + sku);
        ReceiptResult replay = receiving.receive(cmd, "receipt-key-" + sku);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.lots()).extracting(Lot::id).isEqualTo(first.lots().stream().map(Lot::id).toList());
        assertThat(position(sku, "on_hand")).isEqualTo(12);
        assertThatThrownBy(() -> receiving.receive(new ReceiveCommand(cmd.receiptRef(), NODE, null,
                List.of(line(sku, "L-2", 400, 1, 0, 100))), "receipt-key-" + sku))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.idempotency-key-reused"));
    }

    @Test
    void adjustmentsNeverGoBelowReservedAndNeedAReason() {
        String sku = sku("ADJ");
        Lot lot = stock(sku, 10).lots().getFirst();
        reservations.reserve(new ReserveCommand("ORD-" + UUID.randomUUID(), "k",
                List.of(new ReserveCommand.Line(sku, NODE, 8))));

        assertThatThrownBy(() -> lots.adjust(lot.id(), 5, "Cycle count CC-0930", null))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.below-reserved"));
        assertThatThrownBy(() -> lots.adjust(lot.id(), 9, " ", null))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.reason-required"));
        assertThatThrownBy(() -> lots.quarantine(lot.id(), "Broken seals"))
                .isInstanceOfSatisfying(InventoryStateConflict.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.lot-reserved"));

        Lot counted = lots.adjust(lot.id(), 9, "Cycle count CC-0930 variance -1", "cc-" + sku);
        assertThat(counted.qtyOnHand()).isEqualTo(9);
        assertThat(lots.adjust(lot.id(), 9, "Cycle count CC-0930 variance -1", "cc-" + sku).version())
                .isEqualTo(counted.version());
        assertThat(position(sku, "on_hand")).isEqualTo(9);
        assertThat(position(sku, "reserved")).isEqualTo(8);
        assertThat(jdbc.sql("select actor || ':' || reason from stock_movement where sku = :sku and type = 'ADJUST'")
                .param("sku", sku).query(String.class).single()).isEqualTo("local-dev:Cycle count CC-0930 variance -1");
    }

    @Test
    void quarantineReleaseAndWriteOffMoveStockBetweenBuckets() {
        String sku = sku("LOTOPS");
        Lot lot = stock(sku, 12).lots().getFirst();

        assertThat(lots.quarantine(lot.id(), "Broken seals INC-3321").status()).isEqualTo(LotStatus.QUARANTINE);
        assertThat(position(sku, "on_hand")).isZero();
        assertThat(position(sku, "damaged")).isEqualTo(12);
        assertThat(lots.release(lot.id(), "QA passed").status()).isEqualTo(LotStatus.SELLABLE);
        assertThat(position(sku, "on_hand")).isEqualTo(12);

        Lot partial = lots.writeOff(lot.id(), 2, "Crushed cartons", null);
        assertThat(partial.qtyOnHand()).isEqualTo(10);
        assertThat(partial.status()).isEqualTo(LotStatus.SELLABLE);
        Lot gone = lots.writeOff(lot.id(), null, "Recall RC-11", null);
        assertThat(gone.status()).isEqualTo(LotStatus.WRITTEN_OFF);
        assertThat(position(sku, "on_hand")).isZero();
        assertThat(events(InventoryEvents.LOT_WRITTEN_OFF, sku)).isEqualTo(2);
        assertThat(queries.movements(new LedgerRepository.MovementQuery(null, null,
                lot.id(), null, null, null, 0, 50)).items()).extracting(m -> m.type().name())
                .containsExactly("WRITE_OFF", "WRITE_OFF", "RELEASE_QUARANTINE", "QUARANTINE", "PUT_AWAY", "RECEIVE");
    }

    @Test
    void returnsRestockOrHoldAsDamagedButNeverExceedShipped() {
        String sku = sku("RET");
        stock(sku, 5);
        String orderId = "ORD-" + UUID.randomUUID();
        reservations.reserve(new ReserveCommand(orderId, "k", List.of(new ReserveCommand.Line(sku, NODE, 2))));
        reservations.commit(orderId);
        assertThat(position(sku, "on_hand")).isEqualTo(3);

        returns.accept(new ReturnCommand(orderId, sku, null, 1, ReturnCondition.RESTOCK, "Unopened"), "r1-" + sku);
        Lot dmg = returns.accept(new ReturnCommand(orderId, sku, null, 1, ReturnCondition.DAMAGED, "Leaking"),
                "r2-" + sku).lot();
        assertThat(dmg.lotNo()).endsWith("-DMG");
        assertThat(dmg.status()).isEqualTo(LotStatus.DAMAGED);
        assertThat(returns.accept(new ReturnCommand(orderId, sku, null, 1, ReturnCondition.DAMAGED, "Leaking"),
                "r2-" + sku).replayed()).isTrue();
        assertThat(position(sku, "on_hand")).isEqualTo(4);
        assertThat(position(sku, "damaged")).isEqualTo(1);
        assertThatThrownBy(() -> returns.accept(new ReturnCommand(orderId, sku, null, 1, ReturnCondition.RESTOCK,
                "Third unit"), "r3-" + sku))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.return-exceeds-shipped"));
    }

    @Test
    void transferMovesUnreservedUnitsBetweenNodes() {
        String sku = sku("XFER");
        Lot lot = stock(sku, 10).lots().getFirst();
        LotService.TransferResult moved = lots.transfer(lot.id(),
                new LotService.TransferCommand("LAX-FC", "C-01-01", 4, "Rebalance west coast"), "xfer-" + sku);

        assertThat(moved.from().qtyOnHand()).isEqualTo(6);
        assertThat(moved.to().nodeId()).isEqualTo("LAX-FC");
        assertThat(moved.to().lotNo()).isEqualTo(lot.lotNo());
        assertThat(moved.to().qtyOnHand()).isEqualTo(4);
        assertThat(ledgerRows(sku, "TRANSFER")).isEqualTo(2);
        assertThat(jdbc.sql("select on_hand from inventory_position where sku = :sku and node_id = 'LAX-FC'")
                .param("sku", sku).query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void nightlyJobsExpireLotsAndFlagMarkdownIdempotently() {
        String sku = sku("NIGHT");
        ReceiptResult r = receive(NODE, line(sku, "L-STALE", 300, 7, 0, 100), line(sku, "L-SOON", 400, 4, 0, 100));
        UUID stale = r.lots().get(0).id();
        UUID soon = r.lots().get(1).id();
        jdbc.sql("update lot set exp_date = current_date - 1 where id = :id").param("id", stale).update();
        jdbc.sql("update lot set exp_date = current_date + 20 where id = :id").param("id", soon).update();

        assertThat(lifecycle.expireDueLots().expired()).isGreaterThanOrEqualTo(1);
        Lot expired = lots.get(stale);
        assertThat(expired.status()).isEqualTo(LotStatus.EXPIRED);
        assertThat(position(sku, "on_hand")).isEqualTo(4);
        assertThat(position(sku, "damaged")).isEqualTo(7);
        assertThat(ledgerRows(sku, "EXPIRE")).isEqualTo(1);
        assertThat(events(InventoryEvents.LOT_EXPIRED, sku)).isEqualTo(1);
        lifecycle.expireDueLots();
        assertThat(ledgerRows(sku, "EXPIRE")).isEqualTo(1);

        lifecycle.refreshMarkdownFlags();
        assertThat(lots.markdown(MarkdownFlag.WITHIN_30)).extracting(Lot::id).contains(soon).doesNotContain(stale);
        assertThat(lots.get(soon).markdownFlag()).isEqualTo(MarkdownFlag.WITHIN_30);
        assertThat(reconcile().driftUnits()).isZero();
    }

    @Test
    void ledgerIsAppendOnly() {
        String sku = sku("LEDGER");
        stock(sku, 1);
        assertThatThrownBy(() -> jdbc.sql("update stock_movement set qty = 99 where sku = :sku").param("sku", sku)
                .update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from stock_movement where sku = :sku").param("sku", sku).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(ledgerRows(sku, "RECEIVE")).isEqualTo(1);
    }

    @Test
    void reconciliationReportsZeroDriftAndExportsGauge() {
        stock(sku("RECON"), 3);
        assertThat(reconcile().driftUnits()).isZero();
        assertThat(meters.get("inventory.reconciliation.drift").gauge().value()).isZero();
        assertThat(reconciliation.latest()).isPresent();
    }

    @Test
    void sellersSeeOnlyTheirOwnFbaStock() {
        String mine = sku("FBA-MINE");
        String theirs = sku("FBA-THEIRS");
        receiving.receive(new ReceiveCommand("ASN-1", NODE, "org_lagos_textile", List.of(line(mine, "S-1", 400, 5,
                0, 100))), UUID.randomUUID().toString());
        receiving.receive(new ReceiveCommand("ASN-2", NODE, "org_other", List.of(line(theirs, "S-1", 400, 5, 0, 100))),
                UUID.randomUUID().toString());

        TestActors.as(TestActors.seller("org_lagos_textile", "inventory:read"));
        assertThat(queries.positions(new PositionRepository.PositionQuery(null, null, "org_other", false, 0, 200))
                .items()).extracting(p -> p.ownerOrg()).containsOnly("org_lagos_textile");
        UUID theirLot = jdbc.sql("select id from lot where sku = :sku").param("sku", theirs).query(UUID.class)
                .single();
        assertThatThrownBy(() -> lots.get(theirLot)).hasMessageContaining("not found");
        assertThatThrownBy(() -> lots.quarantine(theirLot, "nope")).isInstanceOf(ForbiddenActionException.class);

        TestActors.reset();
        assertThatThrownBy(() -> receiving.receive(new ReceiveCommand("ASN-3", NODE, "org_lagos_textile",
                List.of(line(theirs, "S-2", 400, 1, 0, 100))), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.owner-mismatch"));
    }

    @Test
    void httpAvailabilityAndProblems() throws Exception {
        String sku = sku("HTTP");
        stock(sku, 7);
        HttpResponse<String> availability = get("/v1/availability?skus=" + sku + ",UNKNOWN-SKU");
        assertThat(availability.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(availability.body());
        assertThat(body.get(0).path("available").asInt()).isEqualTo(7);
        assertThat(body.get(0).path("nodes").get(0).path("nodeId").asString()).isEqualTo(NODE);
        assertThat(body.get(1).path("inStock").asBoolean()).isFalse();

        HttpResponse<String> noKey = send(HttpRequest.newBuilder(uri("/v1/reservations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("orderId", "ORD-X",
                        "lines", List.of(Map.of("sku", sku, "qty", 1))))))
                .build());
        assertThat(noKey.statusCode()).isEqualTo(400);

        HttpResponse<String> unknownLot = get("/v1/lots/" + UUID.randomUUID());
        assertThat(unknownLot.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(unknownLot.body()).path("code").asString()).isEqualTo("inventory.lot-not-found");

        HttpResponse<String> docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("/v1/reservations").contains("Idempotency-Key");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
