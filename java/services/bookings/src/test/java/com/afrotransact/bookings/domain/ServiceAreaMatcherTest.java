package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ServiceAreaMatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final AreaPolicy POLICY = AreaPolicy.defaults();
    private static final Zones ZONES = new Zones(List.of(new ServiceZone("greater-austin", "Greater Austin",
            List.of(new ZoneCity("Georgetown", "TX"), new ZoneCity("Round Rock", "TX"), new ZoneCity("Hutto", "TX"),
                    new ZoneCity("Pflugerville", "TX"), new ZoneCity("Taylor", "TX")))));

    private static final CustomerPlace GEORGETOWN = new CustomerPlace("78626", "Georgetown", "TX", 30.6680, -97.5747);
    private static final CustomerPlace TAYLOR = new CustomerPlace("76574", "Taylor", "TX", 30.5700, -97.4100);

    private static ServiceArea area(String city, String state, double lat, double lon, int radius,
                                    NamedArea... extras) {
        return new ServiceArea("org_x", "78664", city, state, lat, lon, radius, List.of(extras), List.of(), NOW);
    }

    private static final ServiceArea ROUND_ROCK = area("Round Rock", "TX", 30.5305, -97.6351, 20);
    private static final ServiceArea BROOKLYN = area("Brooklyn", "NY", 40.7078, -73.9453, 25);

    private static ServiceOffering offering(ServiceMode... modes) {
        return new ServiceOffering(UUID.randomUUID(), "org_x", "Silk press", "", "hair & beauty", 60, 9_500, 2_000,
                "USD", Set.of(modes), List.of(), Set.of(), null, true, NOW, NOW, 0);
    }

    @Test
    void mobileProviderServesCustomersInsideItsRadius() {
        AreaMatch m = ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), ROUND_ROCK, GEORGETOWN, POLICY, ZONES);
        assertThat(m.coverage()).isEqualTo(AreaMatch.Coverage.LOCAL);
        assertThat(m.reason()).isEqualTo(AreaMatch.Reason.RADIUS);
        assertThat(m.miles()).isBetween(9.0, 11.5);
    }

    @Test
    void brooklynNeverServesTexas() {
        for (ServiceMode mode : ServiceMode.values()) {
            AreaMatch m = ServiceAreaMatcher.match(offering(mode), BROOKLYN, GEORGETOWN, POLICY, ZONES);
            assertThat(m.coverage()).as(mode.name()).isEqualTo(AreaMatch.Coverage.NONE);
            assertThat(m.miles()).isGreaterThan(1_400);
        }
    }

    @Test
    void customerTravelLimitCapsShopProvidersAtTheSmallerOfRadiusAndPlatformLimit() {
        ServiceArea wide = area("Round Rock", "TX", 30.5305, -97.6351, 60);
        // Georgetown is ~10 mi away: inside both. Taylor is ~16 mi: inside both.
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON), wide, TAYLOR, POLICY, ZONES).reason())
                .isEqualTo(AreaMatch.Reason.TRAVEL_LIMIT);
        AreaPolicy tight = new AreaPolicy(POLICY.byCategory(), POLICY.fallback(), 12, 60);
        // 12 mi limit: Georgetown (~10) in, Taylor (~16) out of the usual coverage -> nearest suggestion.
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON), wide, GEORGETOWN, tight, ZONES).local())
                .isTrue();
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON), wide, TAYLOR, tight, ZONES).coverage())
                .isEqualTo(AreaMatch.Coverage.NEAREST);
        // The provider's own smaller radius wins over the platform limit.
        ServiceArea small = area("Round Rock", "TX", 30.5305, -97.6351, 5);
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON), small, GEORGETOWN, POLICY, ZONES)
                .coverage()).isEqualTo(AreaMatch.Coverage.NEAREST);
    }

    @Test
    void bothModesMatchWhenEitherDoes() {
        ServiceArea small = area("Round Rock", "TX", 30.5305, -97.6351, 5);
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON, ServiceMode.AT_HOME), ROUND_ROCK,
                GEORGETOWN, POLICY, ZONES).local()).isTrue();
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON, ServiceMode.AT_HOME), small, GEORGETOWN,
                POLICY, ZONES).local()).isFalse();
    }

    @Test
    void namedCityZipAndZoneAreasExtendTheRadius() {
        ServiceArea tiny = area("Round Rock", "TX", 30.5305, -97.6351, 1, new NamedArea(AreaKind.CITY, "georgetown",
                "tx"));
        AreaMatch city = ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), tiny, GEORGETOWN, POLICY, ZONES);
        assertThat(city.reason()).isEqualTo(AreaMatch.Reason.NAMED_AREA);
        ServiceArea zip = area("Round Rock", "TX", 30.5305, -97.6351, 1, new NamedArea(AreaKind.ZIP, "78626", null));
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON), zip, GEORGETOWN, POLICY, ZONES).local())
                .isTrue();
        ServiceArea zone = area("Round Rock", "TX", 30.5305, -97.6351, 1, new NamedArea(AreaKind.ZONE,
                "Greater-Austin", null));
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), zone, TAYLOR, POLICY, ZONES).reason())
                .isEqualTo(AreaMatch.Reason.NAMED_AREA);
        ServiceArea otherState = area("Round Rock", "TX", 30.5305, -97.6351, 1, new NamedArea(AreaKind.CITY,
                "Georgetown", "KY"));
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), otherState, GEORGETOWN, POLICY, ZONES)
                .local()).as("Georgetown, KY is not Georgetown, TX").isFalse();
        ServiceArea unknownZone = area("Round Rock", "TX", 30.5305, -97.6351, 1,
                new NamedArea(AreaKind.ZONE, "no-such-zone", null));
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), unknownZone, GEORGETOWN, POLICY, ZONES)
                .local()).isFalse();
    }

    @Test
    void edgeDistancesAreInclusiveAndTheFallbackHasAConfigurableMaximum() {
        double d = ServiceAreaMatcher.distanceMiles(ROUND_ROCK.lat(), ROUND_ROCK.lon(), GEORGETOWN.lat(),
                GEORGETOWN.lon());
        ServiceArea exact = new ServiceArea("org_x", "78664", "Round Rock", "TX", ROUND_ROCK.lat(), ROUND_ROCK.lon(),
                (int) Math.ceil(d), List.of(), List.of(), NOW);
        ServiceArea justOver = new ServiceArea("org_x", "78664", "Round Rock", "TX", ROUND_ROCK.lat(),
                ROUND_ROCK.lon(), (int) Math.floor(d), List.of(), List.of(), NOW);
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), exact, GEORGETOWN, POLICY, ZONES).local())
                .isTrue();
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), justOver, GEORGETOWN, POLICY, ZONES)
                .coverage()).isEqualTo(AreaMatch.Coverage.NEAREST);
        AreaPolicy noFallback = new AreaPolicy(POLICY.byCategory(), POLICY.fallback(), 30, 0);
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), justOver, GEORGETOWN, noFallback, ZONES)
                .coverage()).isEqualTo(AreaMatch.Coverage.NONE);
        AreaPolicy short30 = new AreaPolicy(POLICY.byCategory(), POLICY.fallback(), 30, 5);
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), justOver, GEORGETOWN, short30, ZONES)
                .coverage()).isEqualTo(AreaMatch.Coverage.NONE);
        assertThat(ServiceAreaMatcher.distanceMiles(30.0, -97.0, 30.0, -97.0)).isZero();
    }

    @Test
    void radiusBoundsFollowTheCategoriesAProviderOffers() {
        assertThat(POLICY.ruleFor("Hair & Beauty")).isEqualTo(new RadiusRule(20, 5, 40));
        assertThat(POLICY.ruleFor("unknown")).isEqualTo(POLICY.fallback());
        RadiusRule both = POLICY.boundsFor(List.of("hair & beauty", "catering"));
        assertThat(both.minMiles()).isEqualTo(5);
        assertThat(both.maxMiles()).isEqualTo(100);
        assertThat(both.defaultMiles()).isEqualTo(40);
        assertThat(POLICY.boundsFor(List.of())).isEqualTo(POLICY.fallback());
        assertThatThrownBy(() -> new RadiusRule(50, 5, 40)).isInstanceOf(BookingsRuleViolation.class);
    }

    @Test
    void zonesFindTheMetroOfACity() {
        assertThat(ZONES.ofCity("round rock", "tx")).map(ServiceZone::name).contains("Greater Austin");
        assertThat(ZONES.ofCity("Brooklyn", "NY")).isEmpty();
    }

    @Test
    void servicesAreInPersonOnly() {
        assertThat(ServiceMode.values()).containsExactly(ServiceMode.IN_PERSON, ServiceMode.AT_HOME);
        assertThat(ServiceMode.parse("at_home")).isEqualTo(ServiceMode.AT_HOME);
        for (String bad : List.of("VIRTUAL", "online", "REMOTE", "video", "", "ONLINE_VIDEO")) {
            assertThatThrownBy(() -> ServiceMode.parse(bad)).isInstanceOf(BookingsRuleViolation.class)
                    .extracting(e -> ((BookingsRuleViolation) e).code()).isEqualTo("bookings.in-person-only");
        }
        assertThatThrownBy(() -> ServiceMode.parse(null)).isInstanceOf(BookingsRuleViolation.class);
    }

    @Test
    void invalidAreasAreRefusedWithFieldErrors() {
        assertThatThrownBy(() -> new ServiceArea("o", "7866", "Round Rock", "TX", 30, -97, 20, List.of(), List.of(),
                NOW)).isInstanceOf(BookingsRuleViolation.class);
        assertThatThrownBy(() -> new ServiceArea("o", "78664", "Round Rock", "TX", 30, -97, 0, List.of(), List.of(),
                NOW)).isInstanceOf(BookingsRuleViolation.class);
        assertThatThrownBy(() -> new NamedArea(AreaKind.CITY, "Taylor", null))
                .isInstanceOf(BookingsRuleViolation.class);
        assertThatThrownBy(() -> new NamedArea(AreaKind.ZIP, "7657", null)).isInstanceOf(BookingsRuleViolation.class);
    }
}
