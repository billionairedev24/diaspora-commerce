package com.afrotransact.deliveryruns.it;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Fake geo-service, a movable clock and a switchable actor for the Spring ITs. */
@TestConfiguration(proxyBeanMethods = false)
public class ItSupport {

    public static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));

    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    public static void as(Actor actor) {
        CURRENT.set(actor);
    }

    public static void reset() {
        CURRENT.set(SUPER_ADMIN);
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }

    @Bean
    @Primary
    FakeGeo fakeGeo() {
        return new FakeGeo();
    }

    @Bean
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
