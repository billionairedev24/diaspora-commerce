package com.afrotransact.offers.it;

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
public class TestActors {

    public static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    public static final Actor ORDERS_SERVICE = new Actor("svc-orders", PrincipalType.SERVICE, Optional.empty(),
            Set.of(), Set.of("offers:read"));

    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    public static void as(Actor actor) {
        CURRENT.set(actor);
    }

    public static void reset() {
        CURRENT.set(SUPER_ADMIN);
    }

    public static Actor seller(String org) {
        return new Actor("user-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of("offers:read", "offers:write", "coupons:write"));
    }

    public static Actor customer(String id) {
        return new Actor(id, PrincipalType.CUSTOMER, Optional.empty(), Set.of("customer"), Set.of());
    }

    public static Actor staff(String... permissions) {
        return new Actor("ops-user", PrincipalType.STAFF, Optional.empty(), Set.of("ops.manager"),
                Set.of(permissions));
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }
}
