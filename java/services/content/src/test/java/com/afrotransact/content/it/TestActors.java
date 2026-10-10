package com.afrotransact.content.it;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Replaces the local super-admin resolver with one tests can switch (applies to HTTP and direct calls). */
@TestConfiguration(proxyBeanMethods = false)
class TestActors {

    static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));

    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);

    static void as(Actor actor) {
        CURRENT.set(actor);
    }

    static void anonymous() {
        CURRENT.set(null);
    }

    static void reset() {
        CURRENT.set(SUPER_ADMIN);
    }

    static Actor staff(String subject, String... permissions) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("marketing"), Set.of(permissions));
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return () -> {
            Actor a = CURRENT.get();
            if (a == null) {
                throw new ForbiddenActionException("auth.unauthenticated", "No authenticated actor");
            }
            return a;
        };
    }
}
