package com.afrotransact.orders.it;

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

    static Actor customer(String subject) {
        return new Actor(subject, PrincipalType.CUSTOMER, Optional.empty(), Set.of("customer"), Set.of());
    }

    static Actor seller(String org) {
        return new Actor("user-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of("orders:read", "orders:update-status"));
    }

    static Actor admin() {
        return new Actor("ops-admin", PrincipalType.STAFF, Optional.empty(), Set.of("platform.operations_manager"),
                Set.of("orders:read", "orders:manage"));
    }

    static Actor fulfilmentOps() {
        return new Actor("fc-ops", PrincipalType.STAFF, Optional.empty(), Set.of("platform.fulfilment_ops"),
                Set.of("orders:read", "fulfilment:operate"));
    }

    static Actor driver() {
        return new Actor("driver-7", PrincipalType.DRIVER, Optional.empty(), Set.of("platform.driver"),
                Set.of("delivery:operate"));
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }
}
