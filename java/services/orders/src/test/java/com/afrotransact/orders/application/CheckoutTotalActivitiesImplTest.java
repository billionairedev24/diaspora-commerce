package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.ShippingAllocation;
import com.afrotransact.orders.port.TaxQuoteClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

/** Never charge more than the total shown, and measure the estimate against the final tax. */
class CheckoutTotalActivitiesImplTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CheckoutTotalActivitiesImpl guard = new CheckoutTotalActivitiesImpl(meters);

    /** One taxable 2 x 10.00 line, delivery 5.99, tax 8.25 % of 25.99 = 2.14: total 28.13. */
    private static OrderDraft draft() {
        CheckoutPlan.Line line = new CheckoutPlan.Line("l1", 1, "ATIN-1", "P-1", "Rice", null, 2, 1000, 0, "groceries",
                false, null, 2, "of-1", 0, "pantry", null, 0, "org", "Seller");
        OrderDraft.DraftShipment s = new OrderDraft.DraftShipment("s1", 1, "org", "Seller",
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.CARRIER, "standard", null,
                DeliveryPreferences.defaults(), false, false, null, null, 599, LocalDate.now(), LocalDate.now(),
                List.of(line));
        OrderDraft d = new OrderDraft("o1", "AT-2026-AAAAAA", "c1", "w1", "USD", new ShipTo("a", "Ada", null, "1 Main St",
                null, "Houston", "TX", "77002", "US", null), false, List.of(s), Instant.now(), null);
        TaxQuoteClient quotes = request -> new TaxQuoteClient.Quote("RULES", request.lines().stream()
                .map(l -> new TaxQuoteClient.LineTax(l.lineId(), 214, 0, "US-TX", null, "RESOLVED", "TAXABLE", "AT.X",
                        "txcd", "v1", null, "ZIP")).toList());
        return new CheckoutTaxImpl(quotes, new TaxSettings(ShippingAllocation.PROPORTIONAL)).tax(d);
    }

    private static CheckoutInput input(CheckoutInput.TaxGuard shown) {
        return new CheckoutInput("o1", "AT-2026-AAAAAA", "c1", "key", "w1", "USD", null, List.of(), List.of(), 900,
                Instant.now(), null, null, Map.of(), null, false, false, shown);
    }

    private double count(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    @Test
    void anOrderWithoutAShownTotalIsNotGuarded() {
        assertThatCode(() -> guard.checkShownTotal(input(null), draft())).doesNotThrowAnyException();
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void anEqualTotalPassesAndCountsAsAMatch() {
        guard.checkShownTotal(input(new CheckoutInput.TaxGuard(2813, 214, true)), draft());
        assertThat(count("checkout.tax.estimate.vs.final", "inputs", "same", "outcome", "match")).isEqualTo(1);
    }

    @Test
    void aLowerTotalPassesToo() {
        // the shopper saw 30.00 with 3.00 tax: paying 28.13 is never more than shown
        guard.checkShownTotal(input(new CheckoutInput.TaxGuard(3000, 300, true)), draft());
        assertThat(count("checkout.tax.estimate.vs.final", "inputs", "same", "outcome", "mismatch")).isEqualTo(1);
        assertThat(meters.get("checkout.tax.estimate.vs.final.delta").tag("inputs", "same").summary().totalAmount())
                .isEqualTo(86);
    }

    @Test
    void aHigherTotalIsRefusedWithTheNewNumbersBeforeAnythingIsCharged() {
        assertThatThrownBy(() -> guard.checkShownTotal(input(new CheckoutInput.TaxGuard(2700, 150, false)), draft()))
                .isInstanceOfSatisfying(ApplicationFailure.class, f -> {
                    assertThat(f.getType()).isEqualTo(CheckoutCodes.TOTAL_CHANGED);
                    assertThat(f.isNonRetryable()).isTrue();
                    String details = f.getDetails().get(0, String.class);
                    assertThat(details).contains("newTotalMinor=2813", "newTaxMinor=214", "newShippingMinor=599",
                            "newItemsMinor=2000", "expectedTotalMinor=2700", "currency=USD");
                    // what the saga stores and the 409 is built from
                    CheckoutFailed failed = CheckoutFailed.of(CheckoutCodes.TOTAL_CHANGED,
                            f.getOriginalMessage() + " (" + String.join(", ", details.split("\n")) + ")", "AT-1");
                    assertThat(failed.getMessage()).isEqualTo("The total for this order changed");
                    assertThat(failed.details()).contains("newTotalMinor=2813", "newTaxMinor=214");
                });
        assertThat(count("checkout.total.refused", "inputs", "changed")).isEqualTo(1);
        assertThat(count("checkout.tax.estimate.vs.final", "inputs", "changed", "outcome", "mismatch")).isEqualTo(1);
    }
}
