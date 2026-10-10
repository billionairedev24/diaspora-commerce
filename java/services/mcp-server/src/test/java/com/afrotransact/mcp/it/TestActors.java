package com.afrotransact.mcp.it;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Replaces the local super-admin resolver with one tests can switch (stands in for the token's claims). */
@TestConfiguration(proxyBeanMethods = false)
class TestActors {

    static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));

    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    static void as(Actor actor) {
        CURRENT.set(actor);
    }

    static void reset() {
        CURRENT.set(SUPER_ADMIN);
    }

    static Actor shopper(String subject) {
        return new Actor(subject, PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer"),
                Set.of("mcp:shopper"));
    }

    static Actor seller(String subject, String org) {
        return new Actor(subject, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of("mcp:shopper", "mcp:seller", "catalog:write", "orders:read", "orders:update-status",
                        "inventory:read"));
    }

    static Actor reviewer(String subject) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("platform.trust_safety"),
                Set.of("mcp:staff", "applications:review"));
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }
}
