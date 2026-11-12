package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderTotals;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Enforces the total the shopper was shown and measures how well the estimate predicted the final tax.
 *
 * <p>Metrics (Prometheus names): {@code checkout_tax_estimate_vs_final_total{inputs,outcome}} counts orders by whether
 * the final tax equalled the estimate ({@code match}) or not ({@code mismatch}), and whether the order was for the inputs
 * the estimate was made for ({@code inputs="same"}) or the shopper changed something since ({@code "changed"});
 * {@code checkout_tax_estimate_vs_final_delta_*} records the absolute tax difference in minor units of every mismatch
 * (its count is the number of mismatches, its sum their amount). {@code checkout_total_refused_total} counts orders
 * refused because the total would be higher. The alert is on {@code inputs="same"} (docs/engineering/checkout-tax-estimate.md).
 */
@Component
@ActivityImpl(taskQueues = CheckoutWorkflow.TASK_QUEUE)
class CheckoutTotalActivitiesImpl implements CheckoutTotalActivities {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutTotalActivitiesImpl.class);

    private final MeterRegistry meters;

    CheckoutTotalActivitiesImpl(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void checkShownTotal(CheckoutInput input, OrderDraft draft) {
        CheckoutInput.TaxGuard shown = input.taxGuard();
        if (shown == null) {
            return;
        }
        Order order = OrderDrafts.toOrder(draft, null);
        OrderTotals now = order.totals();
        verdict(shown, now, input.orderNumber());
        if (now.grandTotalMinor() > shown.expectedTotalMinor()) {
            meters.counter("checkout.total.refused", "inputs", inputs(shown)).increment();
            LOG.info("Checkout {} refused: total {} is above the {} the shopper was shown (tax {} vs {})",
                    input.orderNumber(), now.grandTotalMinor(), shown.expectedTotalMinor(), now.taxMinor(),
                    shown.expectedTaxMinor());
            throw ApplicationFailure.newNonRetryableFailure("The total for this order changed", CheckoutCodes.TOTAL_CHANGED,
                    details(shown, now, order.currency()));
        }
    }

    /** Records whether the final tax equalled the estimate, and by how much it was off when it did not. */
    private void verdict(CheckoutInput.TaxGuard shown, OrderTotals now, String orderNumber) {
        String inputs = inputs(shown);
        long delta = Math.abs(now.taxMinor() - shown.expectedTaxMinor());
        boolean match = delta == 0;
        meters.counter("checkout.tax.estimate.vs.final", "inputs", inputs, "outcome", match ? "match" : "mismatch")
                .increment();
        if (!match) {
            DistributionSummary.builder("checkout.tax.estimate.vs.final.delta")
                    .description("Absolute difference between the estimated and the final tax, per mismatching order")
                    .tag("inputs", inputs).register(meters).record(delta);
            if (shown.inputsMatch()) {
                // The guarantee failed: same inputs, different tax. Loud, so it is looked into.
                LOG.warn("Checkout {}: final tax {} differs from the estimate {} for unchanged inputs", orderNumber,
                        now.taxMinor(), shown.expectedTaxMinor());
            }
        }
    }

    private static String inputs(CheckoutInput.TaxGuard shown) {
        return shown.inputsMatch() ? "same" : "changed";
    }

    /** The new numbers the gateway shows ({@code key=value} per line; see {@link CheckoutFailed#of}). */
    private static String details(CheckoutInput.TaxGuard shown, OrderTotals now, String currency) {
        return String.join("\n", List.of("expectedTotalMinor=" + shown.expectedTotalMinor(),
                "newTotalMinor=" + now.grandTotalMinor(), "newTaxMinor=" + now.taxMinor(),
                "newShippingMinor=" + now.shippingMinor(), "newItemsMinor=" + now.itemsMinor(),
                "newDiscountMinor=" + now.discountMinor(), "currency=" + currency));
    }
}
