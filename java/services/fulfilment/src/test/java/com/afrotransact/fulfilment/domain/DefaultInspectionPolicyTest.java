package com.afrotransact.fulfilment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DefaultInspectionPolicyTest {

    private final InspectionPolicy policy = new DefaultInspectionPolicy();
    private final InspectionContext perishable = new InspectionContext(true, 48_210);
    private final InspectionContext dry = new InspectionContext(false, 48_210);

    private static Map<InspectionItem, CheckResult> allPass() {
        Map<InspectionItem, CheckResult> m = new EnumMap<>(InspectionItem.class);
        for (InspectionItem i : InspectionItem.values()) {
            m.put(i, CheckResult.PASS);
        }
        return m;
    }

    private static Map<PhotoSlot, PhotoCapture> photos(PhotoCapture p) {
        Map<PhotoSlot, PhotoCapture> m = new EnumMap<>(PhotoSlot.class);
        for (PhotoSlot s : PhotoSlot.values()) {
            m.put(s, p);
        }
        return m;
    }

    private static InspectionReport report(Map<InspectionItem, CheckResult> checks, Integer odo, Integer fuel,
                                           Double cold, Map<PhotoSlot, PhotoCapture> photos, String defect) {
        return new InspectionReport("TX-7P10", checks, odo, fuel, cold, photos, defect, true, "sig-1", Photos.AT);
    }

    @Test
    void allPassingWithColdBoxInRangePasses() {
        InspectionOutcome o = policy.evaluate(report(allPass(), 48_222, 75, 37.0, photos(Photos.live("RT-1")), null),
                perishable);
        assertThat(o.result()).isEqualTo(InspectionResult.PASSED);
        assertThat(o.result().allowsRouteStart()).isTrue();
        assertThat(o.warnings()).isEmpty();
    }

    @Test
    void failingBrakesTyresOrLightsBlocksTheRoute() {
        for (InspectionItem critical : new InspectionItem[] {InspectionItem.BRAKES, InspectionItem.TYRES,
            InspectionItem.LIGHTS}) {
            Map<InspectionItem, CheckResult> checks = allPass();
            checks.put(critical, CheckResult.FAIL);
            InspectionOutcome o = policy.evaluate(report(checks, 48_222, 75, 37.0, photos(Photos.live("RT-1")),
                    "Worn"), perishable);
            assertThat(o.result()).isEqualTo(InspectionResult.FAILED_CRITICAL);
            assertThat(o.result().allowsRouteStart()).isFalse();
            assertThat(o.criticalFailures()).containsExactly(critical);
        }
    }

    @Test
    void nonCriticalFailureIsADefectButDoesNotBlock() {
        Map<InspectionItem, CheckResult> checks = allPass();
        checks.put(InspectionItem.HORN_WIPERS, CheckResult.FAIL);
        InspectionOutcome o = policy.evaluate(report(checks, 48_222, 20, null, photos(Photos.live("RT-1")),
                "Left wiper torn"), dry);
        assertThat(o.result()).isEqualTo(InspectionResult.PASSED_WITH_DEFECTS);
        assertThat(o.result().allowsRouteStart()).isTrue();
        assertThat(o.warnings()).anyMatch(w -> w.contains("Low fuel"));
    }

    @Test
    void coldBoxMustReadThirtyThreeToFortyWithPerishables() {
        for (Double bad : new Double[] {null, 32.9, 40.1, 45.0}) {
            assertThatThrownBy(() -> policy.evaluate(report(allPass(), 48_222, 75, bad,
                    photos(Photos.live("RT-1")), null), perishable))
                    .isInstanceOf(FulfilmentRuleViolation.class)
                    .extracting("code").isEqualTo("fulfilment.cold-box-out-of-range");
        }
        assertThat(policy.evaluate(report(allPass(), 48_222, 75, 33.0, photos(Photos.live("RT-1")), null),
                perishable).result()).isEqualTo(InspectionResult.PASSED);
        assertThat(policy.evaluate(report(allPass(), 48_222, 75, 40.0, photos(Photos.live("RT-1")), null),
                perishable).result()).isEqualTo(InspectionResult.PASSED);
        assertThat(policy.evaluate(report(allPass(), 48_222, 75, null, photos(Photos.live("RT-1")), null), dry)
                .result()).as("no perishables: no reading needed").isEqualTo(InspectionResult.PASSED);
    }

    @Test
    void photosMustComeFromTheLiveCamera() {
        assertThatThrownBy(() -> policy.evaluate(report(allPass(), 48_222, 75, 37.0, photos(Photos.gallery("RT-1")),
                null), perishable)).extracting("code").isEqualTo("fulfilment.photo-not-live");
        Map<PhotoSlot, PhotoCapture> three = photos(Photos.live("RT-1"));
        three.remove(PhotoSlot.RIGHT);
        assertThatThrownBy(() -> policy.evaluate(report(allPass(), 48_222, 75, 37.0, three, null), perishable))
                .extracting("code").isEqualTo("fulfilment.inspection-photos-missing");
    }

    @Test
    void incompleteOrInvalidReportsAreRefused() {
        Map<InspectionItem, CheckResult> nine = allPass();
        nine.remove(InspectionItem.PHONE_MOUNT);
        assertThatThrownBy(() -> policy.evaluate(report(nine, 48_222, 75, 37.0, photos(Photos.live("R")), null),
                perishable)).extracting("code").isEqualTo("fulfilment.inspection-incomplete");
        assertThatThrownBy(() -> policy.evaluate(report(allPass(), 48_000, 75, 37.0, photos(Photos.live("R")),
                null), perishable)).extracting("code").isEqualTo("fulfilment.odometer-invalid");
        assertThatThrownBy(() -> policy.evaluate(report(allPass(), 48_222, 101, 37.0, photos(Photos.live("R")),
                null), perishable)).extracting("code").isEqualTo("fulfilment.fuel-invalid");
        Map<InspectionItem, CheckResult> failed = allPass();
        failed.put(InspectionItem.NO_LEAKS, CheckResult.FAIL);
        assertThatThrownBy(() -> policy.evaluate(report(failed, 48_222, 75, 37.0, photos(Photos.live("R")), " "),
                perishable)).extracting("code").isEqualTo("fulfilment.defect-note-required");
        InspectionReport unsigned = new InspectionReport("V", allPass(), 48_222, 75, 37.0, photos(Photos.live("R")),
                null, true, null, Photos.AT);
        assertThatThrownBy(() -> policy.evaluate(unsigned, perishable)).extracting("code")
                .isEqualTo("fulfilment.signature-required");
        InspectionReport uncertified = new InspectionReport("V", allPass(), 48_222, 75, 37.0,
                photos(Photos.live("R")), null, false, "sig", Photos.AT);
        assertThatThrownBy(() -> policy.evaluate(uncertified, perishable)).extracting("code")
                .isEqualTo("fulfilment.inspection-not-certified");
    }
}
