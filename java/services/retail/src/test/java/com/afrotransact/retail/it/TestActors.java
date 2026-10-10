package com.afrotransact.retail.it;

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
class TestActors {

    static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    static final Actor BUYER = staff("ama.buyer", "retail:read", "retail:po:write", "retail:items:write",
            "retail:suppliers:write", "retail:logistics");
    static final Actor LEAD = staff("yaw.lead", "retail:read", "retail:po:approve");
    static final Actor FINANCE = staff("kwesi.finance", "retail:read", "retail:po:approve", "retail:finance");
    static final Actor RECEIVER = staff("dan.receiver", "retail:read", "retail:receive");

    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    static void as(Actor actor) {
        CURRENT.set(actor);
    }

    static void reset() {
        CURRENT.set(SUPER_ADMIN);
    }

    static Actor staff(String subject, String... permissions) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("retail.staff"), Set.of(permissions));
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }
}
