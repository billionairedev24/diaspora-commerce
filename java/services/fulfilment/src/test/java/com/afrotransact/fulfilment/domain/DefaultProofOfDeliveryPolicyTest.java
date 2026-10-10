package com.afrotransact.fulfilment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DefaultProofOfDeliveryPolicyTest {

    private static final String ROUTE = "RT-HOU-PM";

    private final ProofOfDeliveryPolicy policy = new DefaultProofOfDeliveryPolicy();

    private static DeliveryStopFacts stop(boolean perishable, boolean handover, boolean safeAllowed) {
        return new DeliveryStopFacts(ROUTE, perishable, handover, safeAllowed, Photos.ADDRESS, 1, 1);
    }

    private static ProofOfDelivery customer(String code) {
        return new ProofOfDelivery(HandoverMethod.CUSTOMER, code, null, null, null, null, Photos.live(ROUTE));
    }

    @Test
    void handedToCustomerNeedsTheFourDigitCodeWhenRequired() {
        assertThat(policy.check(stop(false, true, false), customer("4821")).distanceMeters()).isLessThan(1.0);
        for (String bad : new String[] {null, "", "48", "48a1", "48210"}) {
            assertThatThrownBy(() -> policy.check(stop(false, true, false), customer(bad)))
                    .extracting("code").isEqualTo("fulfilment.handover-code-required");
        }
        assertThat(policy.check(stop(false, false, true), customer(null))).isNotNull();
    }

    @Test
    void someoneElseNeedsNameRelationshipAndSignature() {
        ProofOfDelivery ok = new ProofOfDelivery(HandoverMethod.SOMEONE_ELSE, null, "Titi A.", "Neighbour",
                "sig-9", null, Photos.live(ROUTE));
        assertThat(policy.check(stop(false, false, false), ok)).isNotNull();
        ProofOfDelivery noName = new ProofOfDelivery(HandoverMethod.SOMEONE_ELSE, null, "T", "Neighbour", "sig-9",
                null, Photos.live(ROUTE));
        assertThatThrownBy(() -> policy.check(stop(false, false, false), noName)).extracting("code")
                .isEqualTo("fulfilment.recipient-proof-required");
        ProofOfDelivery unsigned = new ProofOfDelivery(HandoverMethod.SOMEONE_ELSE, null, "Titi", "Neighbour", null,
                null, Photos.live(ROUTE));
        assertThatThrownBy(() -> policy.check(stop(false, false, false), unsigned)).extracting("code")
                .isEqualTo("fulfilment.signature-required");
    }

    @Test
    void safePlaceIsBlockedForPerishablesAndWhenNotAllowed() {
        ProofOfDelivery door = new ProofOfDelivery(HandoverMethod.SAFE_PLACE, null, null, null, null, "Front door",
                Photos.live(ROUTE));
        assertThat(policy.check(stop(false, false, true), door)).isNotNull();
        assertThatThrownBy(() -> policy.check(stop(true, false, true), door)).extracting("code")
                .isEqualTo("fulfilment.safe-place-not-allowed");
        assertThatThrownBy(() -> policy.check(stop(false, false, false), door)).extracting("code")
                .isEqualTo("fulfilment.safe-place-not-allowed");
        ProofOfDelivery nowhere = new ProofOfDelivery(HandoverMethod.SAFE_PLACE, null, null, null, null, " ",
                Photos.live(ROUTE));
        assertThatThrownBy(() -> policy.check(stop(false, false, true), nowhere)).extracting("code")
                .isEqualTo("fulfilment.safe-place-required");
    }

    @Test
    void photoIsAlwaysRequiredLiveAndStampedWithTimeGpsAndRoute() {
        ProofOfDelivery noPhoto = new ProofOfDelivery(HandoverMethod.CUSTOMER, "4821", null, null, null, null, null);
        assertThatThrownBy(() -> policy.check(stop(false, true, false), noPhoto)).extracting("code")
                .isEqualTo("fulfilment.pod-photo-required");
        ProofOfDelivery gallery = new ProofOfDelivery(HandoverMethod.CUSTOMER, "4821", null, null, null, null,
                Photos.gallery(ROUTE));
        assertThatThrownBy(() -> policy.check(stop(false, true, false), gallery)).extracting("code")
                .isEqualTo("fulfilment.photo-not-live");
        ProofOfDelivery noGps = new ProofOfDelivery(HandoverMethod.CUSTOMER, "4821", null, null, null, null,
                new PhotoCapture("a", CaptureSource.LIVE_CAMERA, Photos.AT, null, null, null, ROUTE, null));
        assertThatThrownBy(() -> policy.check(stop(false, true, false), noGps)).extracting("code")
                .isEqualTo("fulfilment.photo-stamp-missing");
        ProofOfDelivery otherRoute = new ProofOfDelivery(HandoverMethod.CUSTOMER, "4821", null, null, null, null,
                Photos.live("RT-OTHER"));
        assertThatThrownBy(() -> policy.check(stop(false, true, false), otherRoute)).extracting("code")
                .isEqualTo("fulfilment.photo-stamp-route-mismatch");
    }

    @Test
    void photoMustBeTakenWithin150Metres() {
        // ~0.001° latitude ≈ 111 m: inside; 0.002° ≈ 222 m: outside.
        ProofOfDelivery near = new ProofOfDelivery(HandoverMethod.CUSTOMER, "4821", null, null, null, null,
                Photos.liveAt(ROUTE, 29.7386, -95.5134));
        assertThat(policy.check(stop(false, true, false), near).distanceMeters()).isBetween(100.0, 120.0);
        ProofOfDelivery far = new ProofOfDelivery(HandoverMethod.CUSTOMER, "4821", null, null, null, null,
                Photos.liveAt(ROUTE, 29.7396, -95.5134));
        assertThatThrownBy(() -> policy.check(stop(false, true, false), far)).extracting("code")
                .isEqualTo("fulfilment.pod-too-far");
        DeliveryStopFacts notGeocoded = new DeliveryStopFacts(ROUTE, false, true, false, null, 1, 1);
        assertThat(policy.check(notGeocoded, far).distanceMeters()).isNull();
    }

    @Test
    void everyPackageMustBeScannedFirst() {
        DeliveryStopFacts half = new DeliveryStopFacts(ROUTE, false, true, false, Photos.ADDRESS, 2, 1);
        assertThatThrownBy(() -> policy.check(half, customer("4821"))).extracting("code")
                .isEqualTo("fulfilment.scan-all-packages");
    }
}
