package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.TaxCalculator;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.junit.jupiter.api.Test;

class CheckoutTaxImplTest {

    private final List<TaxQuoteClient.Request> requests = new ArrayList<>();
    private RuntimeException failure;

    private final TaxQuoteClient quotes = request -> {
        requests.add(request);
        if (failure != null) {
            throw failure;
        }
        return new TaxQuoteClient.Quote("rules", request.lines().stream().map(l -> new TaxQuoteClient.LineTax(
                l.lineId(), 80 + (l.shippingMinor() > 0 ? 40 : 0), l.shippingMinor() > 0 ? 40 : 0, "US-TX",
                "US-TX-770")).toList());
    };
    private final TaxCalculator table = (country, state, zip) -> new TaxCalculator.Rate(825, "US-" + state);

    private static OrderDraft draft() {
        CheckoutPlan.Line l1 = new CheckoutPlan.Line("l1", 1, "A1", "P1", "Rice", null, 1, 1000, 0, "groceries", false,
                null, 2, null, 100, "grains", null, 0);
        CheckoutPlan.Line l2 = new CheckoutPlan.Line("l2", 2, "A2", "P2", "Oil", null, 2, 500, 0, "groceries", false,
                null, 2, null, 0, "pantry", null, 0);
        OrderDraft.DraftShipment s = new OrderDraft.DraftShipment("s1", 1, "org", "Seller",
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.CARRIER, "standard", null,
                DeliveryPreferences.defaults(), false, false, null, null, 599, LocalDate.now(), LocalDate.now(),
                List.of(l2, l1));
        return new OrderDraft("o1", "AT-2026-AAAAAA", "c1", "w1", "USD", new ShipTo("a", "Ada", null, "1 Main St",
                null, "Houston", "TX", "77002", "US", null), false, List.of(s), Instant.now(), null);
    }

    @Test
    void quotesEveryLineWithTheShipmentShippingOnItsFirstLine() {
        OrderDraft taxed = new CheckoutTaxImpl(quotes, table, new TaxSettings("TAX_RETURNS", true)).tax(draft());
        TaxQuoteClient.Request sent = requests.getFirst();
        assertThat(sent.state()).isEqualTo("TX");
        assertThat(sent.lines()).extracting(TaxQuoteClient.Line::lineId).containsExactly("l1", "l2");
        assertThat(sent.lines()).extracting(TaxQuoteClient.Line::shippingMinor).containsExactly(599L, 0L);
        assertThat(sent.lines().getFirst().discountMinor()).isEqualTo(100);
        assertThat(sent.lines().getFirst().productType()).isEqualTo("grains");
        CheckoutPlan.Line first = taxed.shipments().getFirst().lines().stream()
                .filter(l -> l.lineId().equals("l1")).findFirst().orElseThrow();
        assertThat(first.taxMinor()).isEqualTo(120);
        assertThat(first.shippingTaxMinor()).isEqualTo(40);
        assertThat(first.localTaxJurisdiction()).isEqualTo("US-TX-770");
    }

    @Test
    void fallsBackToTheTableWhenTaxReturnsFailsAndTheFallbackIsOn() {
        failure = new UpstreamFailure("tax-returns", 503, "tax-returns.down", "down", true, List.of(), null);
        OrderDraft taxed = new CheckoutTaxImpl(quotes, table, new TaxSettings("TAX_RETURNS", true)).tax(draft());
        CheckoutPlan.Line first = taxed.shipments().getFirst().lines().stream()
                .filter(l -> l.lineId().equals("l1")).findFirst().orElseThrow();
        assertThat(first.taxMinor()).isEqualTo(74);
        assertThat(first.taxJurisdiction()).isEqualTo("US-TX");
        assertThat(first.shippingTaxMinor()).isZero();
        assertThat(first.localTaxJurisdiction()).isNull();

        CheckoutTax strict = new CheckoutTaxImpl(quotes, table, new TaxSettings("TAX_RETURNS", false));
        assertThatThrownBy(() -> strict.tax(draft())).isInstanceOf(UpstreamFailure.class);
    }

    @Test
    void theTableProviderNeverCallsTaxReturns() {
        new CheckoutTaxImpl(quotes, table, new TaxSettings("TABLE", true)).tax(draft());
        assertThat(requests).isEmpty();
    }
}
