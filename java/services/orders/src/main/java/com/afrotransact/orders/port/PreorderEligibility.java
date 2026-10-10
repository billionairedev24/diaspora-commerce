package com.afrotransact.orders.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import com.afrotransact.orders.domain.PreorderFulfilment;
import com.afrotransact.orders.domain.PreorderStage;

/**
 * delivery-runs {@code GET /v1/service/preorders/eligibility?country=&region=&city=&atins=} (S272) with the
 * orders-service token ({@code delivery-runs:reserve}): which of the cart's products are sold as a pre-order where
 * the customer is, in which window and stage, and the window's fulfilment options and payment setup. Throws
 * {@link UpstreamFailure} when delivery-runs is unavailable.
 */
public interface PreorderEligibility {

    /** Payment method kinds a window may allow (back-office payment setup). */
    Set<String> ALL_METHODS = Set.of("CARD", "WALLET", "ACH");

    /** Default door fee when delivery-runs sends none ($7.99). */
    long DEFAULT_DOOR_FEE_MINOR = 799;

    /** Per variant ATIN; ATINs delivery-runs does not answer for are missing (not a pre-order). */
    Map<String, Line> eligibility(String country, String region, String city, Collection<String> variantAtins);

    /**
     * One product's answer.
     *
     * @param preorder       sold as a pre-order here (category in the window, not flagged shipsNow)
     * @param stage          the window's stage
     * @param deliversOn     delivery date
     * @param closesAt       end of the window (null when not sent)
     * @param door           door delivery offered
     * @param pickup         pickup offered
     * @param doorFeeMinor   door delivery fee (default {@link #DEFAULT_DOOR_FEE_MINOR})
     * @param pickupFeeMinor pickup fee (default 0)
     * @param currency       fee currency (default USD)
     * @param allowedMethods CARD, WALLET, ACH (default all)
     * @param pickupPointId  the window's pickup point (null when not sent)
     */
    record Line(String variantAtin, boolean preorder, String windowId, String windowCode, String windowName,
                PreorderStage stage, LocalDate deliversOn, Instant closesAt, boolean door, boolean pickup,
                long doorFeeMinor, long pickupFeeMinor, String currency, Set<String> allowedMethods,
                String pickupPointId) {

        public Line {
            allowedMethods = allowedMethods == null || allowedMethods.isEmpty() ? ALL_METHODS
                    : Set.copyOf(allowedMethods);
            currency = currency == null || currency.isBlank() ? "USD" : currency;
        }

        /** Whether the window offers this fulfilment. */
        public boolean offers(PreorderFulfilment f) {
            return f == PreorderFulfilment.DOOR ? door : pickup;
        }

        /** The window's fee for this fulfilment. */
        public long feeMinor(PreorderFulfilment f) {
            return f == PreorderFulfilment.DOOR ? doorFeeMinor : pickupFeeMinor;
        }

        /** Default fulfilment: door when offered, else pickup. */
        public PreorderFulfilment defaultFulfilment() {
            return door || !pickup ? PreorderFulfilment.DOOR : PreorderFulfilment.PICKUP;
        }
    }
}
