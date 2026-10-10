package com.afrotransact.fulfilment.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inspection rules from the driver-app design: all ten items answered, odometer not lower than the vehicle's last
 * reading, fuel 0–100 %, cold box 33–40 °F when perishables are on board, four live-camera photos, a defect note when
 * anything failed, certification and signature. Failing brakes, tyres or lights is {@code FAILED_CRITICAL}.
 */
public final class DefaultInspectionPolicy implements InspectionPolicy {

    public static final double COLD_BOX_MIN_F = 33.0;
    public static final double COLD_BOX_MAX_F = 40.0;
    static final int LOW_FUEL_PERCENT = 25;

    @Override
    public InspectionOutcome evaluate(InspectionReport r, InspectionContext ctx) {
        if (Text.blank(r.vehicleId())) {
            throw new FulfilmentRuleViolation("fulfilment.vehicle-required", "Say which vehicle was inspected");
        }
        Set<InspectionItem> missing = EnumSet.allOf(InspectionItem.class);
        missing.removeAll(r.checks().keySet());
        if (!missing.isEmpty()) {
            throw new FulfilmentRuleViolation("fulfilment.inspection-incomplete",
                    "Mark every item pass or fail (" + missing.size() + " left: " + missing + ")");
        }
        if (r.odometerMiles() == null || r.odometerMiles() < 0
                || ctx.lastOdometerMiles() != null && r.odometerMiles() < ctx.lastOdometerMiles()) {
            throw new FulfilmentRuleViolation("fulfilment.odometer-invalid", ctx.lastOdometerMiles() == null
                    ? "Enter a valid odometer reading" : "Odometer must be at least " + ctx.lastOdometerMiles());
        }
        if (r.fuelPercent() == null || r.fuelPercent() < 0 || r.fuelPercent() > 100) {
            throw new FulfilmentRuleViolation("fulfilment.fuel-invalid", "Enter fuel or charge between 0 and 100 %");
        }
        if (ctx.perishableOnBoard() && (r.coldBoxF() == null || r.coldBoxF() < COLD_BOX_MIN_F
                || r.coldBoxF() > COLD_BOX_MAX_F)) {
            throw new FulfilmentRuleViolation("fulfilment.cold-box-out-of-range",
                    "Perishables on board: the cold box must read 33–40 °F");
        }
        for (PhotoSlot slot : PhotoSlot.values()) {
            PhotoCapture p = r.photos().get(slot);
            if (!PhotoCapture.present(p)) {
                throw new FulfilmentRuleViolation("fulfilment.inspection-photos-missing",
                        "Take all 4 vehicle photos (missing " + slot.name().toLowerCase(Locale.ROOT) + ")");
            }
            p.requireLive("Vehicle " + slot.name().toLowerCase(Locale.ROOT));
        }
        List<InspectionItem> failed = new ArrayList<>();
        List<InspectionItem> critical = new ArrayList<>();
        for (InspectionItem item : InspectionItem.values()) {
            if (r.checks().get(item) == CheckResult.FAIL) {
                failed.add(item);
                if (item.critical()) {
                    critical.add(item);
                }
            }
        }
        if (!failed.isEmpty() && Text.blank(r.defectNote())) {
            throw new FulfilmentRuleViolation("fulfilment.defect-note-required", "Describe the defect(s)");
        }
        if (!r.certified()) {
            throw new FulfilmentRuleViolation("fulfilment.inspection-not-certified",
                    "Certify that the vehicle was inspected and the information is accurate");
        }
        if (Text.blank(r.signatureAssetId())) {
            throw new FulfilmentRuleViolation("fulfilment.signature-required", "Sign the inspection");
        }
        List<String> warnings = new ArrayList<>();
        if (r.fuelPercent() < LOW_FUEL_PERCENT) {
            warnings.add("Low fuel — refuel before perishables");
        }
        InspectionResult result = !critical.isEmpty() ? InspectionResult.FAILED_CRITICAL
                : failed.isEmpty() ? InspectionResult.PASSED : InspectionResult.PASSED_WITH_DEFECTS;
        return new InspectionOutcome(result, failed, critical, warnings);
    }
}
