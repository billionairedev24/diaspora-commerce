package com.afrotransact.trustops.it;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Switchable actor (HTTP and direct calls) and a clock that tests can move forward. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupport {

    public static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    public static final MutableClock CLOCK = new MutableClock();
    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    public static void as(Actor actor) {
        CURRENT.set(actor);
    }

    public static void reset() {
        CURRENT.set(SUPER_ADMIN);
        CLOCK.reset();
    }

    /** Trust & Safety staff: identity's platform.trust_safety grants (plus the new sellers:enforce). */
    public static Actor trustSafety(String subject) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("platform.trust_safety"),
                Set.of("catalog:moderate", "disputes:manage", "audit:read", "orgs:read", "sellers:enforce"));
    }

    public static Actor customer(String subject) {
        return new Actor(subject, PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer"), Set.of());
    }

    public static Actor sellerSupport(String org) {
        return new Actor("support-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.support"),
                Set.of("support:respond", "orders:read"));
    }

    public static Actor marketing() {
        return new Actor("mkt-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.marketing"),
                Set.of("marketing:manage"));
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }

    @Bean
    Clock testClock() {
        return CLOCK;
    }

    /** System time shifted by an adjustable offset. */
    public static final class MutableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        public void advance(Duration by) {
            offset.updateAndGet(d -> d.plus(by));
        }

        void reset() {
            offset.set(Duration.ZERO);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.offset(Clock.system(zone), offset.get());
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    }
}
