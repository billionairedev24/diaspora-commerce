package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import com.afrotransact.inventory.application.CommitResult;
import com.afrotransact.inventory.application.ExchangeRateService;
import com.afrotransact.inventory.application.InventoryEvents;
import com.afrotransact.inventory.application.InventoryInsightsService;
import com.afrotransact.inventory.application.LotService;
import com.afrotransact.inventory.application.ReceiptResult;
import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.inventory.domain.CostComponents;
import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.ExchangeRateChange;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.RateSource;
import com.afrotransact.inventory.port.InventoryInsightsRepository;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0005 §7 against real PostgreSQL: stock is received in the currency actually paid (MXN, KES, …) with the rate to
 * USD and the USD value fixed at receiving; the rate defaults to the staff-managed table (audited with a reason);
 * valuation, COGS and the lot events are in USD with the original amount alongside.
 */
class CostCurrencyIT extends InventoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ExchangeRateService rates;
    private LotService lots;
    private ReservationService reservations;
    private InventoryInsightsService insights;

    @BeforeEach
    void wire(@Autowired ExchangeRateService rateService, @Autowired LotService lotService,
              @Autowired ReservationService reservationService, @Autowired InventoryInsightsService insightsService) {
        this.rates = rateService;
        this.lots = lotService;
        this.reservations = reservationService;
        this.insights = insightsService;
    }

    private static ReceiveCommand.Line costed(String sku, String lotNo, int good, long cost, String currency,
                                              BigDecimal rate, LocalDate rateDate, CostComponents parts) {
        return new ReceiveCommand.Line(sku, lotNo, today().minusDays(30), today().plusDays(500), good, 0, "A-01-01",
                cost, currency, null, null, rate, rateDate, null, parts);
    }

    private ReceiptResult receiveFor(String owner, ReceiveCommand.Line... lines) {
        return receiving.receive(new ReceiveCommand("GRN-" + UUID.randomUUID(), NODE, owner, List.of(lines)),
                UUID.randomUUID().toString());
    }

    @Test
    void pesoLotsAreValuedAtTheTablesLatestRateOnOrBeforeToday() {
        jdbc.sql("delete from exchange_rate where currency = 'MXN'").update();
        rates.set("MXN", today().minusDays(20), new BigDecimal("0.0550"), "Banxico FIX", "Opening rate");
        rates.set("MXN", today().minusDays(3), new BigDecimal("0.0577"), "Banxico FIX", "Weekly rate");
        rates.set("MXN", today().plusDays(1), new BigDecimal("0.0999"), "Banxico FIX", "Tomorrow's, entered early");
        String sku = sku("MX-JOL");

        CostComponents parts = new CostComponents(10_000, 1_500, 400, 100);
        Lot lot = receive(NODE, costed(sku, "L-MX-1", 40, 12_000, "MXN", null, null, parts)).lots().getFirst();

        assertThat(lot.currency()).isEqualTo("MXN");
        assertThat(lot.unitLandedCostMinor()).isEqualTo(12_000);
        assertThat(lot.cost().rateToUsd()).isEqualByComparingTo("0.0577");
        assertThat(lot.cost().rateDate()).isEqualTo(today().minusDays(3));
        assertThat(lot.cost().rateSource()).isEqualTo(RateSource.TABLE);
        assertThat(lot.unitCostUsdMinor()).as("MXN 120.00 × 0.0577").isEqualTo(692);

        Lot stored = lots.get(lot.id());
        assertThat(stored.cost()).isEqualTo(lot.cost());
        assertThat(stored.cost().components()).isEqualTo(parts);

        // The USD value is fixed at receiving: a later rate does not move it.
        rates.set("MXN", today().minusDays(1), new BigDecimal("0.0700"), "Banxico FIX", "Peso rallied");
        assertThat(lots.get(lot.id()).unitCostUsdMinor()).isEqualTo(692);

        JsonNode created = lotCreatedPayload(sku);
        assertThat(created.path("currency").asString()).isEqualTo("MXN");
        assertThat(created.path("unitLandedCostMinor").asLong()).isEqualTo(12_000);
        assertThat(created.path("unitCostUsdMinor").asLong()).isEqualTo(692);
        assertThat(created.path("fxRateToUsd").decimalValue()).isEqualByComparingTo("0.0577");
        assertThat(reconcile().clean()).isTrue();
    }

    @Test
    void theRateTheReceiverEntersIsKeptWithItsDateAndWinsOverTheTable() {
        rates.set("KES", today().minusDays(1), new BigDecimal("0.0077"), null, "Opening rate");
        String sku = sku("KE-TEA");

        Lot lot = receive(NODE, costed(sku, "L-KE-1", 10, 30_000, "KES", new BigDecimal("0.0075"),
                today().minusDays(2), null)).lots().getFirst();

        assertThat(lot.cost().rateSource()).isEqualTo(RateSource.ENTERED);
        assertThat(lot.cost().rateDate()).isEqualTo(today().minusDays(2));
        assertThat(lot.cost().rateToUsd()).isEqualByComparingTo("0.0075");
        assertThat(lot.unitCostUsdMinor()).as("KES 300.00 × 0.0075").isEqualTo(225);
        assertThat(lot.cost().components()).as("no breakdown: all goods").isEqualTo(CostComponents.ofTotal(30_000));
    }

    @Test
    void usDollarLotsStayAtRateOne() {
        Lot lot = receive(NODE, line(sku("US-JOL"), "L-US-1", 400, 5, 0, 250)).lots().getFirst();
        assertThat(lot.cost().rateSource()).isEqualTo(RateSource.BASE);
        assertThat(lot.cost().rateToUsd()).isEqualByComparingTo("1");
        assertThat(lot.unitCostUsdMinor()).isEqualTo(250);
    }

    @Test
    void aReceiptThatCannotBeValuedInUsdIsRejectedAsAWholeWithTheFieldsToFix() {
        String sku = sku("BAD-FX");
        assertThatThrownBy(() -> receive(NODE, line(sku, "L-OK", 400, 5, 0, 250),
                costed(sku, "L-ZAR", 5, 1_000, "ZAR", null, null, null),
                costed(sku, "L-JPY", 5, 1_000, "JPY", BigDecimal.ONE, null, null),
                costed(sku, "L-ZERO", 5, 1_000, "MXN", BigDecimal.ZERO, null, null),
                costed(sku, "L-SUM", 5, 1_000, "MXN", new BigDecimal("0.05"), null,
                        new CostComponents(1, 1, 1, 1))))
                .isInstanceOfSatisfying(InventoryRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo("inventory.invalid-cost");
                    assertThat(ex.fieldErrors()).containsOnlyKeys("lines[1].fxRateToUsd", "lines[2].currency",
                            "lines[3].fxRateToUsd", "lines[4].costComponents");
                });
        assertThat(jdbc.sql("select count(*) from lot where sku = :sku").param("sku", sku).query(Long.class)
                .single()).as("nothing was received").isZero();
    }

    @Test
    void valuationIsInUsdWithTheOriginalCurrencyAlongside() {
        String owner = "org_fx_" + UUID.randomUUID().toString().substring(0, 8);
        String sku = sku("VAL");
        receiveFor(owner, costed(sku, "L-V-MXN", 10, 12_000, "MXN", new BigDecimal("0.0577"), null, null),
                costed(sku, "L-V-USD", 5, 0, "USD", null, null, null));
        receiveFor(owner, line(sku("VAL2"), "L-V-US2", 400, 4, 0, 300));

        InventoryInsightsRepository.Overview overview = insights.overview(NODE, owner);

        assertThat(overview.byStatus()).filteredOn(v -> v.status().equals("SELLABLE")).extracting(
                InventoryInsightsRepository.StatusValue::currency,
                InventoryInsightsRepository.StatusValue::valueMinor,
                InventoryInsightsRepository.StatusValue::valueUsdMinor).containsExactly(
                tuple("MXN", 120_000L, 6_920L), tuple("USD", 1_200L, 1_200L));
        InventoryInsightsRepository.NodeFigures node = overview.nodes().getFirst();
        assertThat(node.currency()).isEqualTo("USD");
        assertThat(node.valueMinor()).as("USD value of the node").isEqualTo(6_920 + 1_200);
        assertThat(node.byCurrency()).extracting(InventoryInsightsRepository.CurrencyValue::currency)
                .containsExactly("MXN", "USD");
        assertThat(node.byCurrency().getFirst().valueMinor()).isEqualTo(120_000);
        assertThat(node.byCurrency().getFirst().valueUsdMinor()).isEqualTo(6_920);
    }

    @Test
    void cogsOfAShippedOrderIsTheUsdValueOfTheLotsPickedAndKeepsTheAmountPaid() {
        String sku = sku("COGS");
        // MXN lot expires first so FEFO picks it before the dollar lot.
        receive(NODE, new ReceiveCommand.Line(sku, "L-C-MX", today().minusDays(30), today().plusDays(200), 3, 0, null,
                        12_000, "MXN", null, null, new BigDecimal("0.0577"), null, null, null),
                new ReceiveCommand.Line(sku, "L-C-US", today().minusDays(30), today().plusDays(400), 10, 0, null, 192,
                        "USD", null));
        String orderId = "ORD-" + UUID.randomUUID();
        TestActors.as(TestActors.ORDERS_SERVICE);
        reservations.reserve(new ReserveCommand(orderId, "cogs-" + orderId, List.of(
                new ReserveCommand.Line(sku, NODE, 5, "line-1"))));
        reservations.confirm(orderId);
        CommitResult shipped = reservations.commit(orderId);

        assertThat(shipped.cogsUsdMinor()).isEqualTo(3 * 692 + 2 * 192);
        assertThat(shipped.cogs()).containsExactly(new CommitResult.Money("MXN", 36_000),
                new CommitResult.Money("USD", 384));
        assertThat(shipped.lines().getFirst().cogsUsdMinor()).isEqualTo(2_460);

        TestActors.reset();
        JsonNode committed = JSON.readTree(jdbc.sql("select payload::text from platform_outbox where type = :t "
                        + "and aggregate_id = :id").param("t", InventoryEvents.RESERVATION_COMMITTED)
                .param("id", orderId).query(String.class).single());
        assertThat(committed.path("cogsUsdMinor").asLong()).isEqualTo(2_460);
        JsonNode allocation = committed.path("lines").get(0).path("allocations").get(0);
        assertThat(allocation.path("currency").asString()).isEqualTo("MXN");
        assertThat(allocation.path("unitCostUsdMinor").asLong()).isEqualTo(692);
    }

    @Test
    void aTransferCarriesTheCostAndRateToTheOtherNode() {
        String sku = sku("XFER");
        Lot lot = receive(NODE, costed(sku, "L-X-1", 10, 12_000, "MXN", new BigDecimal("0.0577"), null,
                new CostComponents(10_000, 2_000, 0, 0))).lots().getFirst();

        LotService.TransferResult moved = lots.transfer(lot.id(), new LotService.TransferCommand("EWR-FC", null, 4,
                "Balance stock"), UUID.randomUUID().toString());

        assertThat(moved.to().cost()).isEqualTo(lot.cost());
        assertThat(lots.get(moved.to().id()).cost()).isEqualTo(lot.cost());
    }

    @Test
    void ratesAreCreatedAndChangedWithAReasonAndEveryChangeIsAudited() {
        LocalDate day = today().minusDays(200 + new Random().nextInt(5000));
        ExchangeRate created = rates.set("GHS", day, new BigDecimal("0.0650"), "Bank of Ghana", "Quarter opening");
        assertThat(created.rateToUsd()).isEqualByComparingTo("0.065");
        assertThat(created.version()).isZero();

        ExchangeRate same = rates.set("GHS", day, new BigDecimal("0.065"), "Bank of Ghana", "Nothing changed");
        assertThat(same.version()).as("an unchanged rate is not a change").isZero();

        ExchangeRate changed = rates.set("GHS", day, new BigDecimal("0.0660"), "Bank of Ghana", "Corrected a typo");
        assertThat(changed.rateToUsd()).isEqualByComparingTo("0.066");
        assertThat(changed.version()).isEqualTo(1);
        assertThat(changed.updatedBy()).isEqualTo("local-dev");

        List<ExchangeRateChange> history = rates.history("GHS", 0, 50).items().stream()
                .filter(c -> c.rateDate().equals(day)).toList();
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().action().name()).isEqualTo("UPDATE");
        assertThat(history.getFirst().oldRate()).isEqualByComparingTo("0.065");
        assertThat(history.getFirst().newRate()).isEqualByComparingTo("0.066");
        assertThat(history.getFirst().reason()).isEqualTo("Corrected a typo");
        assertThat(history.getLast().action().name()).isEqualTo("CREATE");
        assertThat(history.getLast().oldRate()).isNull();

        assertThat(rates.list("GHS", 0, 10).items()).extracting(ExchangeRate::rateDate).contains(day);
        assertThat(rates.current(day.plusDays(5))).filteredOn(r -> r.currency().equals("GHS")).singleElement()
                .satisfies(r -> assertThat(r.rateToUsd()).isEqualByComparingTo("0.066"));
        assertThat(rates.current(day.minusDays(1))).as("nothing before the first rate")
                .noneMatch(r -> r.currency().equals("GHS") && r.rateDate().equals(day));

        assertThatThrownBy(() -> jdbc.sql("update exchange_rate_audit set reason = 'x'").update())
                .as("the audit is append-only").hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from exchange_rate_audit").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void rateChangesNeedAReasonAKnownCurrencyAPositiveRateAndTheRightPermission() {
        LocalDate day = today().minusDays(2);
        assertThatThrownBy(() -> rates.set("MXN", day, new BigDecimal("0.0577"), null, " "))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.reason-required"));
        assertThatThrownBy(() -> rates.set("MXN", day, BigDecimal.ZERO, null, "Because"))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.invalid-rate"));
        assertThatThrownBy(() -> rates.set("MXN", day, new BigDecimal("-1"), null, "Because"))
                .isInstanceOf(InventoryRuleViolation.class);
        assertThatThrownBy(() -> rates.set("JPY", day, BigDecimal.ONE, null, "Because"))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.invalid-currency"));
        assertThatThrownBy(() -> rates.set("USD", day, BigDecimal.ONE, null, "Because"))
                .isInstanceOf(InventoryRuleViolation.class);
        assertThatThrownBy(() -> rates.set("MXN", today().plusDays(5), new BigDecimal("0.05"), null, "Because"))
                .isInstanceOf(InventoryRuleViolation.class);

        TestActors.as(TestActors.staff("inventory:read"));
        assertThatThrownBy(() -> rates.set("MXN", day, new BigDecimal("0.0577"), null, "Because"))
                .isInstanceOf(ForbiddenActionException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("inventory:adjust"));
        assertThat(rates.list(null, 0, 5)).isNotNull();
        TestActors.as(TestActors.seller("org_some_seller", "inventory:read"));
        assertThatThrownBy(() -> rates.list(null, 0, 5)).as("rates are for staff").isInstanceOf(
                ForbiddenActionException.class);
        assertThat(rates.currencies()).as("sellers may read the list of currencies").isNotEmpty();
    }

    @Test
    void theCurrencyListIsTheConfiguredOneWithUsdFirstAndMinorUnits() {
        assertThat(rates.currencies()).extracting(c -> c.code()).startsWith("USD").contains("MXN", "NGN", "GHS", "CAD",
                "EUR", "GBP");
        assertThat(rates.currencies().getFirst().base()).isTrue();
        assertThat(rates.currencies()).filteredOn(c -> c.code().equals("XOF")).singleElement()
                .satisfies(c -> assertThat(c.minorUnits()).isZero());
        assertThat(rates.currencies().getFirst().name().toLowerCase(Locale.ROOT)).contains("dollar");
    }

    private JsonNode lotCreatedPayload(String sku) {
        return JSON.readTree(jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id")
                .param("t", InventoryEvents.LOT_CREATED).param("id", sku).query(String.class).list().getFirst());
    }
}
