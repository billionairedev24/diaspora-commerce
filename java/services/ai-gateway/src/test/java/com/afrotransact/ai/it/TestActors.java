package com.afrotransact.ai.it;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Replaces the local super-admin resolver with one tests can switch. */
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

    static Actor seller(String subject, String... permissions) {
        return new Actor(subject, PrincipalType.SELLER_MEMBER, Optional.of("org_test"), Set.of("seller.owner"),
                Set.of(permissions));
    }

    static Actor customer(String subject) {
        return new Actor(subject, PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer"), Set.of());
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }
}
