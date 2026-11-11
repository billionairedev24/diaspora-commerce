package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ServiceAreaMatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final AreaPolicy POLICY = AreaPolicy.defaults();
    private static final ShopperRadius R25 = new ShopperRadius(25);
    private static final ShopperRadius R5 = new ShopperRadius(5);
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
        AreaMatch m = ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), ROUND_ROCK, GEORGETOWN, R25, ZONES);
        assertThat(m.coverage()).isEqualTo(AreaMatch.Coverage.LOCAL);
        assertThat(m.reason()).isEqualTo(AreaMatch.Reason.RADIUS);
        assertThat(m.miles()).isBetween(9.0, 11.5);
    }

    @Test
    void brooklynNeverServesTexas() {
        for (ServiceMode mode : ServiceMode.values()) {
            AreaMatch m = ServiceAreaMatcher.match(offering(mode), BROOKLYN, GEORGETOWN, R25, ZONES);
            assertThat(m.coverage()).as(mode.name()).isEqualTo(AreaMatch.Coverage.NONE);
            assertThat(m.miles()).isGreaterThan(1_400);
        }
    }

    @Test
    void shopTypeServicesFollowTheShoppersChosenDistanceNotTheProvidersRadiusOrAPlatformLimit() {
        // Round Rock to Georgetown is ~10 mi and to Taylor ~16 mi.
        ServiceArea tinyRadius = area("Round Rock", "TX", 30.5305, -97.6351, 5);
        ServiceOffering shop = offering(ServiceMode.IN_PERSON);
        AreaMatch near = ServiceAreaMatcher.match(shop, tinyRadius, GEORGETOWN, new ShopperRadius(25), ZONES);
        assertThat(near.local()).as("the provider's 5 mi radius does not stop a shopper who will go 25").isTrue();
        assertThat(near.reason()).isEqualTo(AreaMatch.Reason.SHOPPER_RADIUS);
        assertThat(ServiceAreaMatcher.match(shop, tinyRadius, GEORGETOWN, new ShopperRadius(5), ZONES).local())
                .as("a shopper who will go only 5 mi does not reach a provider 10 mi away").isFalse();
        // Past the old 30 mile limit: nothing caps the shopper, "Any distance" even reaches Brooklyn.
        assertThat(ServiceAreaMatcher.match(shop, BROOKLYN, GEORGETOWN, new ShopperRadius(100), ZONES).local())
                .isFalse();
        assertThat(ServiceAreaMatcher.match(shop, BROOKLYN, GEORGETOWN, new ShopperRadius(1_000), ZONES).local())
                .isFalse();
        assertThat(ServiceAreaMatcher.match(shop, BROOKLYN, GEORGETOWN, ShopperRadius.ANY, ZONES).local()).isTrue();
        ServiceArea farTx = area("Houston", "TX", 29.7543, -95.3577, 5);
        assertThat(ServiceAreaMatcher.match(shop, farTx, GEORGETOWN, new ShopperRadius(100), ZONES).local())
                .as("Houston is ~150 mi: beyond 100").isFalse();
        assertThat(ServiceAreaMatcher.match(shop, farTx, GEORGETOWN, new ShopperRadius(200), ZONES).local()).isTrue();
        assertThat(ServiceAreaMatcher.match(shop, farTx, GEORGETOWN, ShopperRadius.ANY, ZONES).reason())
                .isEqualTo(AreaMatch.Reason.SHOPPER_RADIUS);
    }

    @Test
    void homeServicesKeepTheProvidersOwnRadiusWhateverTheShopperChose() {
        ServiceOffering home = offering(ServiceMode.AT_HOME);
        ServiceArea r8 = area("Round Rock", "TX", 30.5305, -97.6351, 8);
        ServiceArea r12 = area("Round Rock", "TX", 30.5305, -97.6351, 12);
        for (ShopperRadius shopper : List.of(new ShopperRadius(5), new ShopperRadius(25), new ShopperRadius(100),
                ShopperRadius.ANY)) {
            assertThat(ServiceAreaMatcher.match(home, r8, GEORGETOWN, shopper, ZONES).local())
                    .as("8 mi radius, shopper %d", shopper.miles()).isFalse();
            assertThat(ServiceAreaMatcher.match(home, r12, GEORGETOWN, shopper, ZONES).local())
                    .as("12 mi radius, shopper %d", shopper.miles()).isTrue();
        }
    }

    @Test
    void everyCombinationOfModeProviderRadiusAndShopperDistance() {
        // d is ~10.4 mi between Round Rock and Georgetown.
        double d = ServiceAreaMatcher.distanceMiles(ROUND_ROCK.lat(), ROUND_ROCK.lon(), GEORGETOWN.lat(),
                GEORGETOWN.lon());
        int inside = (int) Math.ceil(d) + 1;
        int outside = (int) Math.floor(d) - 1;
        for (boolean home : List.of(false, true)) {
            for (boolean shop : List.of(false, true)) {
                if (!home && !shop) {
                    continue;
                }
                Set<ServiceMode> modes = new HashSet<>();
                if (home) {
                    modes.add(ServiceMode.AT_HOME);
                }
                if (shop) {
                    modes.add(ServiceMode.IN_PERSON);
                }
                ServiceOffering o = offering(modes.toArray(new ServiceMode[0]));
                for (boolean providerReaches : List.of(false, true)) {
                    ServiceArea a = area("Round Rock", "TX", 30.5305, -97.6351, providerReaches ? inside : outside);
                    for (int shopper : List.of(outside, inside, 0)) {
                        boolean shopperReaches = shopper == 0 || shopper >= d;
                        boolean expected = (home && providerReaches) || (shop && shopperReaches);
                        assertThat(ServiceAreaMatcher.match(o, a, GEORGETOWN, new ShopperRadius(shopper), ZONES)
                                .local()).as("home=%s shop=%s providerReaches=%s shopper=%d", home, shop,
                                providerReaches, shopper).isEqualTo(expected);
                    }
                }
            }
        }
    }

    @Test
    void shopperRadiusIsValidated() {
        assertThat(ShopperRadius.ANY.any()).isTrue();
        assertThat(new ShopperRadius(5).any()).isFalse();
        assertThat(new ShopperRadius(1_000).reaches(999.9)).isTrue();
        assertThatThrownBy(() -> new ShopperRadius(-1)).isInstanceOf(BookingsRuleViolation.class)
                .extracting(e -> ((BookingsRuleViolation) e).code()).isEqualTo("bookings.invalid-radius");
        assertThatThrownBy(() -> new ShopperRadius(1_001)).isInstanceOf(BookingsRuleViolation.class);
    }

    @Test
    void bothModesMatchWhenEitherDoes() {
        ServiceArea small = area("Round Rock", "TX", 30.5305, -97.6351, 5);
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON, ServiceMode.AT_HOME), ROUND_ROCK,
                GEORGETOWN, R5, ZONES).local()).isTrue();
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON, ServiceMode.AT_HOME), small, GEORGETOWN,
                R5, ZONES).local()).isFalse();
    }

    @Test
    void namedCityZipAndZoneAreasExtendTheRadius() {
        ServiceArea tiny = area("Round Rock", "TX", 30.5305, -97.6351, 1, new NamedArea(AreaKind.CITY, "georgetown",
                "tx"));
        AreaMatch city = ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), tiny, GEORGETOWN, R5, ZONES);
        assertThat(city.reason()).isEqualTo(AreaMatch.Reason.NAMED_AREA);
        ServiceArea zip = area("Round Rock", "TX", 30.5305, -97.6351, 1, new NamedArea(AreaKind.ZIP, "78626", null));
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.IN_PERSON), zip, GEORGETOWN, R5, ZONES).local())
                .isTrue();
        ServiceArea zone = area("Round Rock", "TX", 30.5305, -97.6351, 1, new NamedArea(AreaKind.ZONE,
                "Greater-Austin", null));
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), zone, TAYLOR, R5, ZONES).reason())
                .isEqualTo(AreaMatch.Reason.NAMED_AREA);
        ServiceArea otherState = area("Round Rock", "TX", 30.5305, -97.6351, 1, new NamedArea(AreaKind.CITY,
                "Georgetown", "KY"));
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), otherState, GEORGETOWN, R5, ZONES)
                .local()).as("Georgetown, KY is not Georgetown, TX").isFalse();
        ServiceArea unknownZone = area("Round Rock", "TX", 30.5305, -97.6351, 1,
                new NamedArea(AreaKind.ZONE, "no-such-zone", null));
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), unknownZone, GEORGETOWN, R5, ZONES)
                .local()).isFalse();
    }

    @Test
    void edgeDistancesAreInclusive() {
        double d = ServiceAreaMatcher.distanceMiles(ROUND_ROCK.lat(), ROUND_ROCK.lon(), GEORGETOWN.lat(),
                GEORGETOWN.lon());
        ServiceArea exact = new ServiceArea("org_x", "78664", "Round Rock", "TX", ROUND_ROCK.lat(), ROUND_ROCK.lon(),
                (int) Math.ceil(d), List.of(), List.of(), NOW);
        ServiceArea justOver = new ServiceArea("org_x", "78664", "Round Rock", "TX", ROUND_ROCK.lat(),
                ROUND_ROCK.lon(), (int) Math.floor(d), List.of(), List.of(), NOW);
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), exact, GEORGETOWN, R25, ZONES).local())
                .isTrue();
        assertThat(ServiceAreaMatcher.match(offering(ServiceMode.AT_HOME), justOver, GEORGETOWN, R25, ZONES).local())
                .isFalse();
        assertThat(new ShopperRadius((int) Math.ceil(d)).reaches(d)).isTrue();
        assertThat(new ShopperRadius((int) Math.floor(d)).reaches(d)).isFalse();
        assertThat(new ShopperRadius(10).reaches(10.0)).as("exactly the radius still matches").isTrue();
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
        assertThat(POLICY.ruleFor("catering").defaultMiles()).isEqualTo(40);
        assertThat(POLICY.ruleFor("tailoring").defaultMiles()).isEqualTo(25);
        assertThat(POLICY.ruleFor("tax & legal").defaultMiles()).isEqualTo(30);
        assertThat(POLICY.ruleFor("lawn care").defaultMiles()).as("others").isEqualTo(25);
        assertThat(POLICY.widestDefaultMiles()).isEqualTo(40);
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
