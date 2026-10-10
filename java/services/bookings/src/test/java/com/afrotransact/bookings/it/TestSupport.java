package com.afrotransact.bookings.it;

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

/** Switchable actor (HTTP and direct calls) and an injectable clock that tests can move forward. */
@TestConfiguration(proxyBeanMethods = false)
class TestSupport {

    static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    static final Set<String> ALL = Set.of("bookings:read", "bookings:manage", "bookings:services:write",
            "bookings:availability:write", "bookings:team:manage");

    static final MutableClock CLOCK = new MutableClock();
    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    static void as(Actor actor) {
        CURRENT.set(actor);
    }

    static void reset() {
        CURRENT.set(SUPER_ADMIN);
        CLOCK.reset();
    }

    static Actor owner(String org) {
        return new Actor("owner-" + org, PrincipalType.PROVIDER_MEMBER, Optional.of(org), Set.of("provider.owner"),
                ALL);
    }

    static Actor practitioner(String org, String userId) {
        return new Actor(userId, PrincipalType.PROVIDER_MEMBER, Optional.of(org), Set.of("provider.practitioner"),
                Set.of("bookings:read", "bookings:manage", "bookings:availability:write"));
    }

    static Actor finance(String org) {
        return new Actor("finance-" + org, PrincipalType.PROVIDER_MEMBER, Optional.of(org), Set.of("provider.finance"),
                Set.of("bookings:read"));
    }

    /** Trust and safety staff with the review moderation permission. */
    static Actor moderator() {
        return new Actor("staff-ts-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.trust_safety"),
                Set.of("bookings:reviews:moderate"));
    }

    /** The trust-ops service client deciding from its moderation queue. */
    static Actor trustOps() {
        return new Actor("trust-ops-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("bookings:reviews:moderate"));
    }

    static Actor customer(String subject) {
        return new Actor(subject, PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of());
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
    static final class MutableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        void advance(Duration by) {
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
