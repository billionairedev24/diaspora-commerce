package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.taxreturns.application.LiabilityReport;
import com.afrotransact.taxreturns.application.OrderIntakeService;
import com.afrotransact.taxreturns.application.PaymentRefunded;
import com.afrotransact.taxreturns.application.ReversalLine;
import com.afrotransact.taxreturns.application.TaxLedgerService;
import com.afrotransact.taxreturns.application.TaxQuoteCommand;
import com.afrotransact.taxreturns.application.TaxService;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.LiabilityRow;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.domain.tax.TaxTransactionKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** S116 against PostgreSQL: seeded rate table, quotes, sale recording, refund reversals and liability reports. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
@Import(StubUpstreams.class)
class TaxLedgerIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private final TaxService tax;
    private final TaxLedgerService ledger;
    private final OrderIntakeService intake;
    private final TestActors actors;
    private final JdbcClient jdbc;

    @Autowired
    TaxLedgerIT(TaxService tax, TaxLedgerService ledger, OrderIntakeService intake, TestActors actors,
                JdbcClient jdbc) {
        this.tax = tax;
        this.ledger = ledger;
        this.intake = intake;
        this.actors = actors;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void asStaff() {
        actors.staff();
    }

    private static TaxQuoteCommand.Line line(String id, String category, String productType, long unit) {
        return new TaxQuoteCommand.Line(id, "AT" + id, "org_a", category, productType, 1, unit, 0, 0);
    }

    @Test
    void quotesFromTheSeededRateTable() {
        actors.service("tax:quote");
        TaxQuote houston = tax.quote(new TaxQuoteCommand("usd", new TaxAddress("US", "TX", "77019", "Houston"),
                List.of(line("1", null, "dress", 6_499), line("2", null, "grocery", 1_850),
                        line("3", "PREPARED_FOOD", null, 1_200))));
        assertThat(houston.currency()).isEqualTo("USD");
        assertThat(houston.collectedBy()).isEqualTo(CollectedBy.MARKETPLACE);
        assertThat(houston.lines()).extracting(TaxLineResult::taxMinor).containsExactly(536L, 0L, 99L);
        assertThat(houston.lines().get(1).category().name()).isEqualTo("GROCERY");
        assertThat(houston.lines()).extracting(TaxLineResult::taxCode).containsExactly("CLOTHING", "PACKAGED_FOOD",
                null);
        assertThat(houston.lines().getFirst().localJurisdiction()).isEqualTo("US-TX-770");

        TaxQuote nyc = tax.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "NY", "10001", null),
                List.of(line("1", null, null, 10_000))));
        assertThat(nyc.lines().getFirst().rate()).isEqualByComparingTo("0.08875");
        assertThat(nyc.totalTaxMinor()).isEqualTo(888);

        TaxQuote delaware = tax.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "DE", "19901", null),
                List.of(line("1", null, null, 10_000))));
        assertThat(delaware.collectedBy()).isEqualTo(CollectedBy.NOT_COLLECTED);
        assertThat(delaware.totalTaxMinor()).isZero();

        assertThatThrownBy(() -> tax.quote(new TaxQuoteCommand("dollars", null, List.of())))
                .isInstanceOfSatisfying(ReturnRuleViolation.class, ex -> assertThat(ex.fields())
                        .containsExactlyInAnyOrder("currency", "shipTo", "lines"));
        actors.customer("cust_1");
        assertThatThrownBy(() -> tax.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "TX", "77019", null),
                List.of(line("1", null, null, 100))))).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void rateTableIsConfigurable() {
        assertThat(tax.rates().states()).extracting(StateTaxRule::state).contains("TX", "NY", "CA", "GA");
        tax.upsertLocal(new LocalTaxRate("TX", "78701", "Austin, TX", new BigDecimal("0.02"), false));
        actors.service("tax:quote");
        assertThat(tax.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "TX", "78701", null),
                List.of(line("1", null, null, 10_000)))).totalTaxMinor()).isEqualTo(825);
        actors.staff();
        tax.deleteLocal("TX", "78701");

        StateTaxRule tx = tax.rates().states().stream().filter(s -> s.state().equals("TX")).findFirst().orElseThrow();
        StateTaxRule stale = new StateTaxRule("US", "TX", "Texas", tx.generalRate(), tx.groceryRate(),
                tx.preparedFoodRate(), tx.shippingTaxable(), tx.facilitator(), tx.version() + 5);
        assertThatThrownBy(() -> tax.upsertState(stale)).isInstanceOf(ConflictException.class);
        StateTaxRule updated = tax.upsertState(new StateTaxRule("US", "TX", "Texas", tx.generalRate(),
                tx.groceryRate(), tx.preparedFoodRate(), tx.shippingTaxable(), tx.facilitator(), tx.version()));
        assertThat(updated.version()).isEqualTo(tx.version() + 1);

        actors.seller("org_a", "payouts:read");
        assertThatThrownBy(() -> tax.rates()).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void salesAndRefundReversalsMakeTheMonthlyLiability() {
        Orders order = new Orders("cust_tax");
        Instant placed = Instant.now();
        YearMonth month = YearMonth.from(placed.atZone(ZoneOffset.UTC));
        LiabilityReport before = ledger.liability(month);
        long txBefore = net(before, "US-TX");

        assertThat(intake.onOrderPlaced(UUID.randomUUID(), order.placed(placed))).isTrue();
        assertThat(ledger.forOrder(order.orderId)).hasSize(3).allSatisfy(t -> {
            assertThat(t.kind()).isEqualTo(TaxTransactionKind.SALE);
            assertThat(t.jurisdiction()).isEqualTo("US-TX");
            assertThat(t.localJurisdiction()).isEqualTo("US-TX-770");
        });
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'tax.sale.recorded' "
                + "and aggregate_id = :id").param("id", order.orderId.toString()).query(Long.class).single())
                .isEqualTo(1L);

        UUID refundEvent = UUID.randomUUID();
        PaymentRefunded refunded = new PaymentRefunded(order.orderId.toString(), "pref-tax-1", "USD", "SUCCEEDED",
                placed, List.of(new PaymentRefunded.Line(order.dress, 6_499, 536)));
        assertThat(ledger.onPaymentRefunded(refundEvent, refunded)).isTrue();
        assertThat(ledger.onPaymentRefunded(refundEvent, refunded)).as("redelivery").isFalse();
        assertThat(ledger.onPaymentRefunded(UUID.randomUUID(), refunded)).as("same refund, new event").isTrue();
        assertThat(ledger.forOrder(order.orderId)).filteredOn(t -> t.kind() == TaxTransactionKind.REFUND)
                .singleElement().satisfies(t -> {
                    assertThat(t.taxMinor()).isEqualTo(-536);
                    assertThat(t.taxableMinor()).isEqualTo(-6_499);
                    assertThat(t.sellerOrgId()).isEqualTo("org_a");
                });

        ledger.onPaymentRefunded(UUID.randomUUID(), new PaymentRefunded(order.orderId.toString(), "pref-tax-2",
                "USD", "SUCCEEDED", placed, List.of(new PaymentRefunded.Line(order.pillow, 3_200, 264))));
        ledger.onPaymentRefunded(UUID.randomUUID(), new PaymentRefunded(order.orderId.toString(), "pref-tax-2",
                "USD", "FAILED", placed, List.of()));

        LiabilityReport after = ledger.liability(month);
        LiabilityRow texas = row(after, "US-TX");
        assertThat(texas.netTaxMinor() - txBefore).isEqualTo(1_072 + 264 - 536);
        assertThat(texas.taxCollectedMinor()).isGreaterThanOrEqualTo(1_336);
        assertThat(texas.taxRefundedMinor()).isGreaterThanOrEqualTo(536);

        LiabilityReport orgA = ledger.sellerStatement("org_a", month);
        LiabilityReport orgB = ledger.sellerStatement("org_b", month);
        assertThat(net(orgA, "US-TX")).isGreaterThanOrEqualTo(1_072 - 536);
        assertThat(net(orgB, "US-TX")).isGreaterThanOrEqualTo(264);

        actors.seller("org_b", "payouts:read");
        assertThat(ledger.sellerStatement("org_b", month).sellerOrgId()).isEqualTo("org_b");
        assertThatThrownBy(() -> ledger.sellerStatement("org_a", month)).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> ledger.liability(month)).isInstanceOf(ForbiddenActionException.class);

        actors.staff();
        assertThatThrownBy(() -> ledger.reverse(order.orderId, "manual-1", "USD",
                List.of(new ReversalLine(order.pillow, 3_200, 300)))).isInstanceOf(ReturnRuleViolation.class);
        assertThat(ledger.reverse(order.orderId, "manual-1", "USD",
                List.of(new ReversalLine(order.pillow, 3_200, 264)))).isEqualTo(1);
        assertThat(ledger.reverse(order.orderId, "manual-1", "USD",
                List.of(new ReversalLine(order.pillow, 3_200, 264)))).as("idempotent").isZero();
    }

    private static LiabilityRow row(LiabilityReport r, String jurisdiction) {
        return r.rows().stream().filter(x -> x.jurisdiction().equals(jurisdiction)).findFirst().orElseThrow();
    }

    private static long net(LiabilityReport r, String jurisdiction) {
        return r.rows().stream().filter(x -> x.jurisdiction().equals(jurisdiction))
                .mapToLong(LiabilityRow::netTaxMinor).sum();
    }
}
