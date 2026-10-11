package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.Money;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.WindowSwitch;

/**
 * A window as the back office shows it.
 *
 * @param window       the aggregate
 * @param status       derived status now
 * @param checks       failed publish checks (empty when it can be published)
 * @param doorFee      effective door fee (override, else the platform fee for the currency; null when none is set)
 * @param pickupFee    effective pickup fee
 * @param pickupWindow pickup hours
 * @param count        pre-orders (paid + late adds)
 * @param revenueMinor their totals incl. delivery
 * @param areaOff      the master switch is off or every target area is switched off
 * @param switchState  which switch holds the window off the storefront (ON when it is live)
 */
public record WindowView(PreorderWindow window, PreorderStatus status, List<RuleViolation> checks, Money doorFee,
                         Money pickupFee, String pickupWindow, int count, long revenueMinor, boolean areaOff,
                         WindowSwitch switchState) {
}
