package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Plan entitlements are read from the plan's limits; only what a plan allows is listed as its features. */
class PlanEntitlementsTest {

    @Test
    void aPlanWithoutEntitlementsIsNotCappedAndGetsTheBasicAssistant() {
        PlanEntitlements none = PlanEntitlements.of(Map.of());
        assertThat(none.listings()).isNull();
        assertThat(none.aiListingWriter()).isFalse();
        assertThat(none.lolaTier()).isEqualTo(PlanEntitlements.LolaTier.ESSENTIALS);
        assertThat(none.allowsAnotherListing(1_000_000)).isTrue();
    }

    @Test
    void theListingCapIsANumberOrUnlimited() {
        PlanEntitlements capped = PlanEntitlements.of(Map.of("listings", "1,000", "aiListingWriter", "TRUE",
                "lolaTier", "growth"));
        assertThat(capped.listings()).isEqualTo(1000);
        assertThat(capped.aiListingWriter()).isTrue();
        assertThat(capped.lolaTier()).isEqualTo(PlanEntitlements.LolaTier.GROWTH);
        assertThat(capped.allowsAnotherListing(999)).isTrue();
        assertThat(capped.allowsAnotherListing(1000)).as("the 1,001st is over the cap").isFalse();
        assertThat(PlanEntitlements.of(Map.of("listings", "Unlimited")).listings()).isNull();
        assertThat(PlanEntitlements.of(Map.of("listings", "0")).allowsAnotherListing(0)).as("a cap of zero")
                .isFalse();
    }

    @Test
    void malformedValuesTakeTheSafeDefaultButAreReportedOnSave() {
        Map<String, String> bad = Map.of("listings", "lots", "aiListingWriter", "maybe", "lolaTier", "GOLD");
        PlanEntitlements read = PlanEntitlements.of(bad);
        assertThat(read.listings()).isNull();
        assertThat(read.aiListingWriter()).isFalse();
        assertThat(read.lolaTier()).isEqualTo(PlanEntitlements.LolaTier.ESSENTIALS);
        assertThat(PlanEntitlements.validate(bad)).containsKeys("limits.listings", "limits.aiListingWriter",
                "limits.lolaTier");
        assertThat(PlanEntitlements.validate(Map.of("listings", "50", "aiListingWriter", "false", "lolaTier", "PRO",
                "support", "Priority"))).isEmpty();
        assertThat(PlanEntitlements.validate(Map.of("listings", "-5"))).containsKey("limits.listings");
        assertThat(PlanEntitlements.validate(Map.of("listings", "99999999999"))).containsKey("limits.listings");
    }

    @Test
    void capabilitiesAreOffUnlessThePlanSwitchesThemOn() {
        PlanEntitlements none = PlanEntitlements.of(Map.of("listings", "50"));
        assertThat(none.bulkUpload()).isFalse();
        assertThat(none.imageLibrary()).isFalse();
        assertThat(none.coupons()).isFalse();
        PlanEntitlements on = PlanEntitlements.of(Map.of("bulkUpload", "true", "imageLibrary", "TRUE", "coupons",
                "true"));
        assertThat(on.bulkUpload()).isTrue();
        assertThat(on.imageLibrary()).isTrue();
        assertThat(on.coupons()).isTrue();
        assertThat(PlanEntitlements.validate(Map.of("bulkUpload", "yes"))).containsKey("limits.bulkUpload");
    }

    @Test
    void lolaCanBeSwitchedOffForAPlan() {
        PlanEntitlements off = PlanEntitlements.of(Map.of("lolaTier", "none"));
        assertThat(off.lolaTier()).isEqualTo(PlanEntitlements.LolaTier.NONE);
        assertThat(off.lolaIncluded()).isFalse();
        assertThat(PlanEntitlements.of(Map.of("lolaTier", "PRO")).lolaIncluded()).isTrue();
        assertThat(PlanEntitlements.validate(Map.of("lolaTier", "NONE"))).isEmpty();
    }

    @Test
    void theFeatureListNamesOnlyWhatThePlanAllows() {
        assertThat(PlanEntitlements.of(Map.of("listings", "50", "lolaTier", "NONE")).featureList())
                .containsExactly("Up to 50 listings");
        assertThat(PlanEntitlements.of(Map.of("listings", "1,000", "bulkUpload", "true", "imageLibrary", "true",
                "coupons", "true", "aiListingWriter", "true", "lolaTier", "GROWTH")).featureList())
                .containsExactly("Up to 1,000 listings", "Bulk upload", "Image library", "Coupons",
                        "AI listing writer", "Lola Growth assistant");
        assertThat(PlanEntitlements.of(Map.of("listings", "unlimited", "lolaTier", "NONE")).featureList())
                .containsExactly("Unlimited listings");
    }
}
