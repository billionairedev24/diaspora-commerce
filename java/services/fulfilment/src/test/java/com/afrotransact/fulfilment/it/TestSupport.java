package com.afrotransact.fulfilment.it;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Replaces the local super-admin resolver with one tests can switch (applies to HTTP and direct calls). */
@TestConfiguration(proxyBeanMethods = false)
class TestSupport {

    static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));

    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    static void as(Actor actor) {
        CURRENT.set(actor);
    }

    static void reset() {
        CURRENT.set(SUPER_ADMIN);
    }

    static Actor fulfilmentOps() {
        return new Actor("fc-ops", PrincipalType.STAFF, Optional.empty(), Set.of("platform.fulfilment_ops"),
                Set.of("fulfilment:operate", "delivery:dispatch", "orders:read"));
    }

    static Actor dispatcher() {
        return new Actor("dispatcher-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.dispatcher"),
                Set.of("delivery:dispatch", "orders:read"));
    }

    static Actor driver(String id) {
        return new Actor(id, PrincipalType.DRIVER, Optional.empty(), Set.of("platform.driver"),
                Set.of("delivery:operate"));
    }

    /** The same person after identity removed delivery:operate (their next token). */
    static Actor exDriver(String id) {
        return new Actor(id, PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("orders:read"));
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }
}
