package com.afrotransact.payments.it;

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
    static final Actor ORDERS_SERVICE = new Actor("svc-orders", PrincipalType.SERVICE, Optional.empty(), Set.of(),
            Set.of("payments:service"));
    static final Actor FINANCE = new Actor("fin-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.finance"),
            Set.of("payments:read", "payments:configure", "payouts:approve", "payouts:request"));

    static final MutableClock CLOCK = new MutableClock();
    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    static void as(Actor actor) {
        CURRENT.set(actor);
    }

    static void reset() {
        CURRENT.set(SUPER_ADMIN);
        CLOCK.reset();
    }

    static Actor seller(String org, String role, String... permissions) {
        return new Actor("user-" + org + "-" + role, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of(role),
                Set.of(permissions));
    }

    static Actor customer(String subject) {
        return new Actor(subject, PrincipalType.CUSTOMER, Optional.empty(), Set.of("customer"), Set.of());
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

        void setNow(Instant target) {
            offset.set(Duration.between(Instant.now(), target));
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
