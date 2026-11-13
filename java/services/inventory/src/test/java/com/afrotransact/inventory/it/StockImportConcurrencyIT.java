package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.inventory.application.StockImportService;
import com.afrotransact.inventory.domain.InsufficientStockException;
import com.afrotransact.inventory.domain.ReconciliationReport;
import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.platform.web.error.ConflictException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Stock CSV import against real PostgreSQL with live reservations (S395): a count never takes stock below the units
 * reserved for orders, whatever the order of the import and the checkouts; every change is one ledger entry with the reason
 * BULK_COUNT and the import id; the same file twice changes nothing more; metrics carry only the outcome.
 */
class StockImportConcurrencyIT extends InventoryIntegrationTest {

    private StockImportService imports;
    private ReservationService reservations;
    private MeterRegistry meters;

    @BeforeEach
    void wire(@Autowired StockImportService importService, @Autowired ReservationService reservationService,
              @Autowired MeterRegistry registry) {
        this.imports = importService;
        this.reservations = reservationService;
        this.meters = registry;
    }

    private static String newOrg() {
        return "org_count_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Stock owned by {@code org} at the test node, as an FBA receipt makes it. */
    private void stockFor(String org, String sku, int units) {
        receiving.receive(new ReceiveCommand("GRN-" + UUID.randomUUID(), NODE, org,
                List.of(line(sku, "L-" + UUID.randomUUID().toString().substring(0, 6), 500, units, 0, 250))),
                UUID.randomUUID().toString());
    }

    private static byte[] csv(String header, List<String> rows) {
        return (header + "\r\n" + String.join("\r\n", rows) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private StockImport uploadAndStart(String org, byte[] file, StockImportMode mode, boolean validOnly) {
        TestActors.as(TestActors.seller(org, "inventory:inbound", "inventory:read"));
        StockImport uploaded = imports.upload("count.csv", file, mode, null);
        imports.start(uploaded.id(), validOnly);
        TestActors.reset();
        return uploaded;
    }

    private void awaitDone(UUID id) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < until) {
            String status = jdbc.sql("select status from stock_import where id = :id").param("id", id).query(String.class)
                    .single();
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                assertThat(status).isEqualTo("COMPLETED");
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("stock import " + id + " did not finish in 60 s");
    }

    private String outcome(UUID id, String sku) {
        return jdbc.sql("select outcome from stock_import_row where import_id = :id and sku = :sku").param("id", id)
                .param("sku", sku).query(String.class).single();
    }

    private String note(UUID id, String sku) {
        return jdbc.sql("select coalesce(outcome_note, '') from stock_import_row where import_id = :id and sku = :sku")
                .param("id", id).param("sku", sku).query(String.class).single();
    }

    private long violations(List<String> skus) {
        return jdbc.sql("select (select count(*) from inventory_position where sku in (:skus) and reserved > on_hand)"
                + " + (select count(*) from lot where sku in (:skus) and qty_reserved > qty_on_hand)")
                .param("skus", skus).query(Long.class).single();
    }

    @Test
    void liveReservationsNeverLetACountTakeStockBelowReserved() throws Exception {
        String org = newOrg();
        int skuCount = 8;
        int onHand = 40;
        int reservedFirst = 10;
        List<String> skus = new ArrayList<>();
        for (int i = 0; i < skuCount; i++) {
            String sku = sku("CNT");
            skus.add(sku);
            stockFor(org, sku, onHand);
            reservations.reserve(new ReserveCommand("ORD-" + UUID.randomUUID(), "k-" + sku,
                    List.of(new ReserveCommand.Line(sku, NODE, reservedFirst))));
        }
        // Even SKUs are counted at 5 (below the 10 already reserved: never allowed), odd ones at 25 (allowed unless
        // checkouts get to reserve more than 25 first).
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < skuCount; i++) {
            rows.add(skus.get(i) + "," + NODE + "," + (i % 2 == 0 ? 5 : 25));
        }

        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicLong badSamples = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        StockImport started;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> checkouts = new ArrayList<>();
            for (String sku : skus) {
                for (int b = 0; b < 30; b++) {
                    int n = b;
                    checkouts.add(pool.submit(() -> {
                        go.await();
                        try {
                            reservations.reserve(new ReserveCommand("ORD-" + UUID.randomUUID(), "c-" + sku + "-" + n,
                                    List.of(new ReserveCommand.Line(sku, NODE, 1))));
                            reserved.incrementAndGet();
                        } catch (InsufficientStockException ex) {
                            refused.incrementAndGet();
                        }
                        return null;
                    }));
                }
            }
            // A watcher reads committed state all the time: reserved may never exceed on hand, not even for an instant.
            Future<?> watcher = pool.submit(() -> {
                go.await();
                long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (System.nanoTime() < until && checkouts.stream().anyMatch(f -> !f.isDone())) {
                    badSamples.addAndGet(violations(skus));
                    Thread.sleep(5);
                }
                return null;
            });
            started = uploadAndStart(org, csv("Seller SKU,Location,On hand", rows), StockImportMode.ABSOLUTE, false);
            go.countDown();
            for (Future<?> f : checkouts) {
                f.get();
            }
            watcher.get();
        }
        awaitDone(started.id());

        assertThat(badSamples.get()).as("reserved above on hand at some moment").isZero();
        assertThat(violations(skus)).isZero();
        int applied = 0;
        for (int i = 0; i < skuCount; i++) {
            String sku = skus.get(i);
            int target = i % 2 == 0 ? 5 : 25;
            int onHandNow = position(sku, "on_hand");
            int reservedNow = position(sku, "reserved");
            assertThat(reservedNow).as(sku).isLessThanOrEqualTo(onHandNow);
            if (i % 2 == 0) {
                assertThat(outcome(started.id(), sku)).as(sku).isEqualTo("FAILED");
                assertThat(note(started.id(), sku)).contains("reserved for orders");
                assertThat(onHandNow).as("a refused count leaves the stock alone").isEqualTo(onHand);
            } else if ("APPLIED".equals(outcome(started.id(), sku))) {
                applied++;
                assertThat(onHandNow).as("applied: on hand is the count").isEqualTo(target);
                assertThat(reservedNow).as("checkouts after the count stop at the count").isLessThanOrEqualTo(target);
            } else {
                assertThat(outcome(started.id(), sku)).isEqualTo("FAILED");
                assertThat(note(started.id(), sku)).contains("reserved for orders");
                assertThat(onHandNow).isEqualTo(onHand);
                assertThat(reservedNow).as("refused because checkouts had reserved more than the count").isGreaterThan(target);
            }
            // The ledger: one ADJUST per applied row, with the reason and the import id; nothing for refused rows.
            long adjust = ledgerRows(sku, "ADJUST");
            assertThat(adjust).isEqualTo("APPLIED".equals(outcome(started.id(), sku)) ? 1 : 0);
        }
        assertThat(reserved.get() + refused.get()).isEqualTo(skuCount * 30);
        assertThat(jdbc.sql("select count(*) from stock_movement where reference = :ref and type = 'ADJUST'"
                + " and reason like 'BULK_COUNT%' and owner_org = :org").param("ref", started.id().toString())
                .param("org", org).query(Long.class).single()).isEqualTo(applied);
        ReconciliationReport report = reconcile();
        assertThat(report.driftUnits()).as(report.drifts().toString()).isZero();
    }

    @Test
    void aCountAtOrAboveTheReservedUnitsIsAppliedAndFurtherCheckoutsStopAtTheNewCount() throws Exception {
        String org = newOrg();
        String sku = sku("EDGE");
        stockFor(org, sku, 50);
        reservations.reserve(new ReserveCommand("ORD-" + UUID.randomUUID(), "k1", List.of(new ReserveCommand.Line(sku, NODE, 30))));

        StockImport equal = uploadAndStart(org, csv("Seller SKU,Location,On hand", List.of(sku + "," + NODE + ",30")),
                StockImportMode.ABSOLUTE, false);
        awaitDone(equal.id());

        assertThat(outcome(equal.id(), sku)).isEqualTo("APPLIED");
        assertThat(position(sku, "on_hand")).isEqualTo(30);
        assertThat(position(sku, "reserved")).isEqualTo(30);
        assertThatThrownBy(() -> reservations.reserve(new ReserveCommand("ORD-" + UUID.randomUUID(), "k2",
                List.of(new ReserveCommand.Line(sku, NODE, 1))))).isInstanceOf(InsufficientStockException.class);
        // The ledger entry names the reason and the import.
        Map<String, Object> entry = jdbc.sql("select reason, reference, delta_on_hand, on_hand_before, on_hand_after, actor from"
                + " stock_movement where sku = :sku and type = 'ADJUST'").param("sku", sku).query().singleRow();
        assertThat(entry.get("reason").toString()).startsWith("BULK_COUNT").contains(equal.id().toString());
        assertThat(entry.get("reference")).isEqualTo(equal.id().toString());
        assertThat(entry).containsEntry("delta_on_hand", -20).containsEntry("on_hand_before", 50).containsEntry("on_hand_after", 30);
    }

    @Test
    void aChangeThatWouldLeaveLessThanReservedIsAFailedRowForAbsoluteAndDelta() throws Exception {
        String org = newOrg();
        String sku = sku("LOW");
        stockFor(org, sku, 50);
        reservations.reserve(new ReserveCommand("ORD-" + UUID.randomUUID(), "k1", List.of(new ReserveCommand.Line(sku, NODE, 30))));

        StockImport absolute = uploadAndStart(org, csv("Seller SKU,Location,On hand", List.of(sku + "," + NODE + ",29")),
                StockImportMode.ABSOLUTE, false);
        awaitDone(absolute.id());
        StockImport delta = uploadAndStart(org, csv("Seller SKU,Location,Change", List.of(sku + "," + NODE + ",-21")),
                StockImportMode.DELTA, false);
        awaitDone(delta.id());

        for (UUID id : List.of(absolute.id(), delta.id())) {
            assertThat(outcome(id, sku)).isEqualTo("FAILED");
            assertThat(note(id, sku)).contains("30 units reserved for orders");
        }
        assertThat(position(sku, "on_hand")).isEqualTo(50);
        assertThat(ledgerRows(sku, "ADJUST")).isZero();
    }

    @Test
    void theSameFileTwiceChangesNothingMoreAndADeltaFileIsNeverAppliedTwice() throws Exception {
        String org = newOrg();
        String sku = sku("ONCE");
        stockFor(org, sku, 50);
        byte[] absolute = csv("Seller SKU,Location,On hand", List.of(sku + "," + NODE + ",44"));

        StockImport first = uploadAndStart(org, absolute, StockImportMode.ABSOLUTE, false);
        awaitDone(first.id());
        StockImport again = uploadAndStart(org, absolute, StockImportMode.ABSOLUTE, false);
        awaitDone(again.id());

        assertThat(again.sameFileAs()).isEqualTo(first.id());
        assertThat(outcome(first.id(), sku)).isEqualTo("APPLIED");
        assertThat(outcome(again.id(), sku)).isEqualTo("UNCHANGED");
        assertThat(ledgerRows(sku, "ADJUST")).isEqualTo(1);

        byte[] change = csv("Seller SKU,Location,Change", List.of(sku + "," + NODE + ",-4"));
        StockImport delta = uploadAndStart(org, change, StockImportMode.DELTA, false);
        awaitDone(delta.id());
        assertThat(position(sku, "on_hand")).isEqualTo(40);
        TestActors.as(TestActors.seller(org, "inventory:inbound", "inventory:read"));
        StockImport deltaAgain = imports.upload("count.csv", change, StockImportMode.DELTA, null);
        assertThatThrownBy(() -> imports.start(deltaAgain.id(), false)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("inventory.import-already-applied"));
        assertThat(position(sku, "on_hand")).as("never applied twice").isEqualTo(40);
        assertThat(ledgerRows(sku, "ADJUST")).isEqualTo(2);
    }

    @Test
    void anotherOrganisationsSkuLooksUnknownAndItsStockIsNeverTouched() throws Exception {
        String owner = newOrg();
        String other = newOrg();
        String sku = sku("MINE");
        stockFor(owner, sku, 50);

        TestActors.as(TestActors.seller(other, "inventory:inbound", "inventory:read"));
        StockImport upload = imports.upload("count.csv", csv("Seller SKU,Location,On hand", List.of(sku + "," + NODE + ",1")),
                StockImportMode.ABSOLUTE, owner);
        TestActors.reset();

        assertThat(upload.ownerOrg()).as("the organisation is the caller's, whatever was asked").isEqualTo(other);
        assertThat(upload.errorRows()).isEqualTo(1);
        assertThat(upload.validRows()).isZero();
        assertThat(position(sku, "on_hand")).isEqualTo(50);
    }

    @Test
    void importMetricsCarryOnlyTheOutcomeNeverAnOrganisationSkuOrImportId() throws Exception {
        String org = newOrg();
        String sku = sku("MET");
        stockFor(org, sku, 10);
        StockImport upload = uploadAndStart(org, csv("Seller SKU,Location,On hand", List.of(sku + "," + NODE + ",12")),
                StockImportMode.ABSOLUTE, false);
        awaitDone(upload.id());

        List<Meter> importMeters = meters.getMeters().stream().filter(m -> m.getId().getName().startsWith("inventory.stock.import"))
                .toList();
        assertThat(importMeters).isNotEmpty();
        for (Meter m : importMeters) {
            assertThat(m.getId().getTags()).as(m.getId().toString()).allSatisfy(t -> {
                assertThat(t.getKey()).isEqualTo("outcome");
                assertThat(t.getValue()).isIn("APPLIED", "UNCHANGED", "FAILED", "SKIPPED");
            });
        }
        assertThat(meters.get("inventory.stock.import.rows").tag("outcome", "APPLIED").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(upload.reference().toUpperCase(Locale.ROOT)).startsWith("SI-");
    }
}
