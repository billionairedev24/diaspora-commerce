package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.afrotransact.content.domain.market.Capabilities;
import com.afrotransact.content.domain.market.Capability;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.Eligibility;
import com.afrotransact.content.domain.market.MarketChanges;
import com.afrotransact.content.domain.market.MarketSnapshot;
import com.afrotransact.content.domain.market.MarketStatus;
import com.afrotransact.content.domain.market.Region;
import com.afrotransact.content.domain.market.RegionKind;
import org.junit.jupiter.api.Test;

class MarketsTest {

    private static final Region TX = new Region("US", "TX", "Texas", RegionKind.STATE, Capabilities.allOn(),
            List.of("733", "750", "770", "885"));
    private static final Region GA = new Region("US", "GA", "Georgia", RegionKind.STATE,
            new Capabilities(true, true, false, true, true), List.of("300", "303"));
    private static final Region AK = new Region("US", "AK", "Alaska", RegionKind.STATE, Capabilities.allOff(),
            List.of("995"));
    private static final Country US = new Country("US", "United States", MarketStatus.LIVE, null, "USD", 1,
            List.of(TX, GA, AK));
    private static final Country CA = new Country("CA", "Canada", MarketStatus.COMING_SOON,
            "Early access — 90 days at 0% commission", "CAD", 2, List.of(new Region("CA", "ON", "Ontario",
            RegionKind.PROVINCE, Capabilities.allOff(), List.of("K", "L", "M", "N", "P"))));
    private static final Country GB = new Country("GB", "United Kingdom", MarketStatus.PLANNED, null, "GBP", 3, List.of());
    private static final Country FR = new Country("FR", "France", MarketStatus.PAUSED, null, "EUR", 1000, List.of());
    private static final MarketSnapshot SNAPSHOT = new MarketSnapshot(7, Instant.parse("2026-10-01T00:00:00Z"), "ops",
            List.of(US, CA, GB, FR));

    @Test
    void marketplaceOffTurnsEverythingOff() {
        Capabilities off = Capabilities.allOn().with(Capability.MARKETPLACE, false);
        assertThat(off).isEqualTo(Capabilities.allOff());
        assertThat(new Capabilities(false, true, true, true, true)).isEqualTo(Capabilities.allOff());
        assertThat(off.with(Capability.MARKETPLACE, true))
                .isEqualTo(new Capabilities(true, false, false, false, false));
    }

    @Test
    void enablingACapabilityNeedsTheMarketplace() {
        assertThatThrownBy(() -> Capabilities.allOff().with(Capability.FOOD, true))
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("content.marketplace-off"));
        assertThatThrownBy(() -> Capabilities.strict(false, false, false, true, false))
                .isInstanceOf(ContentRuleViolation.class);
        assertThat(Capabilities.allOff().with(Capability.FOOD, false)).isEqualTo(Capabilities.allOff());
        assertThat(Capabilities.allOn().with(Capability.PICKUP, false).pickup()).isFalse();
    }

    @Test
    void activateAndDeactivateAllApplyToEveryRegion() {
        assertThat(US.activateAll().regions()).allSatisfy(r -> assertThat(r.capabilities()).isEqualTo(Capabilities.allOn()));
        assertThat(US.deactivateAll().regions()).allSatisfy(r -> assertThat(r.capabilities()).isEqualTo(Capabilities.allOff()));
        assertThat(US.deactivateAll().status()).isEqualTo(MarketStatus.LIVE);
    }

    @Test
    void liveStateIsEligibleByStateOrZip() {
        Eligibility byState = SNAPSHOT.eligibility("us", "tx", null);
        assertThat(byState.deliveryAllowed()).isTrue();
        assertThat(byState.onboardingAllowed()).isTrue();
        assertThat(byState.region()).isEqualTo("TX");
        assertThat(byState.reason()).isEqualTo("ok");
        assertThat(byState.marketsVersion()).isEqualTo(7);
        Eligibility byZip = SNAPSHOT.eligibility("US", null, "77042");
        assertThat(byZip.region()).isEqualTo("TX");
        assertThat(byZip.deliveryAllowed()).isTrue();
        assertThat(SNAPSHOT.eligibility("US", null, "30303").capabilities().food()).isFalse();
    }

    @Test
    void disabledStateBlocksDelivery() {
        Eligibility alaska = SNAPSHOT.eligibility("US", null, "99501");
        assertThat(alaska.region()).isEqualTo("AK");
        assertThat(alaska.deliveryAllowed()).isFalse();
        assertThat(alaska.onboardingAllowed()).isFalse();
        assertThat(alaska.reason()).isEqualTo("region-disabled");
        assertThat(alaska.capabilities()).isEqualTo(Capabilities.allOff());
    }

    @Test
    void mismatchedOrUnknownLocations() {
        assertThat(SNAPSHOT.eligibility("US", "GA", "77042").reason()).isEqualTo("postal-code-mismatch");
        assertThat(SNAPSHOT.eligibility("US", "ZZ", null).reason()).isEqualTo("region-unknown");
        assertThat(SNAPSHOT.eligibility("US", null, "00000").reason()).isEqualTo("postal-code-unknown");
        Eligibility countryOnly = SNAPSHOT.eligibility("US", null, null);
        assertThat(countryOnly.onboardingAllowed()).isTrue();
        assertThat(countryOnly.region()).isNull();
    }

    @Test
    void nonLiveCountriesRejectOnboardingAndOfferTheLaunchList() {
        Eligibility canada = SNAPSHOT.eligibility("CA", "ON", "M5V 2T6");
        assertThat(canada.onboardingAllowed()).isFalse();
        assertThat(canada.deliveryAllowed()).isFalse();
        assertThat(canada.reason()).isEqualTo("country-not-live");
        assertThat(canada.countryStatus()).isEqualTo(MarketStatus.COMING_SOON);
        assertThat(canada.launchNote()).contains("0% commission");
        assertThat(canada.launchListOpen()).isTrue();
        assertThat(SNAPSHOT.eligibility("GB", null, null).launchListOpen()).isTrue();
        assertThat(SNAPSHOT.eligibility("FR", null, null).launchListOpen()).isFalse();
        Eligibility unknown = SNAPSHOT.eligibility("XX", null, null);
        assertThat(unknown.reason()).isEqualTo("unsupported-country");
        assertThat(unknown.countryStatus()).isNull();
    }

    @Test
    void changesBetweenPublishedAndDraft() {
        Country draftUs = US.withRegion(TX.withCapabilities(Capabilities.allOff()))
                .withRegion(AK.withCapabilities(Capabilities.allOn()));
        Country draftCa = CA.withStatus(MarketStatus.LIVE, "Now live");
        MarketChanges changes = MarketChanges.between(SNAPSHOT.countries(), List.of(draftUs, draftCa, GB, FR));
        assertThat(changes.changedCountries()).containsExactly("US", "CA");
        assertThat(changes.newlyLive()).containsExactly("CA");
        assertThat(changes.disabledRegions()).containsExactly("US-TX");
        assertThat(changes.enabledRegions()).containsExactly("US-AK");
        assertThat(MarketChanges.between(SNAPSHOT.countries(), SNAPSHOT.countries()).empty()).isTrue();
    }
}
