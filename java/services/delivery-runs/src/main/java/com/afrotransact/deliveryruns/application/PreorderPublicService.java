package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.PreorderResolver.Eligibility;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Resolution;

/** Storefront (no sign-in) and orders-service (delivery-runs:reserve) pre-order reads. */
public interface PreorderPublicService {

    /** What the storefront shows for a delivery place (no PII). */
    Resolution resolve(String country, String region, String city);

    /** Open and upcoming windows for the {@code /pre-orders} page. */
    List<OpenWindow> open();

    /** Per-ATIN pre-order eligibility at a place, with the effective fees orders-service charges. */
    EligibilityResult eligibility(String country, String region, String city, List<String> atins);

    /**
     * An open or upcoming window.
     *
     * @param window    the window
     * @param stage     OPEN or UPCOMING
     * @param doorFee   effective door fee (null without door delivery)
     * @param pickupFee effective pickup fee (null without pickup)
     */
    record OpenWindow(PreorderWindow window, PreorderStatus stage, FeeSnapshot doorFee, FeeSnapshot pickupFee) {
    }

    /**
     * @param resolution the place's resolution (window, stage, fees)
     * @param items      one entry per requested ATIN (unknown ATINs are not pre-order)
     */
    record EligibilityResult(Resolution resolution, List<Eligibility> items) {
    }
}
