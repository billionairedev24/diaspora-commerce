package com.afrotransact.seller.it;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.seller.port.CallerProfile;
import com.afrotransact.seller.port.CallerProfileProvider;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Switchable actor and identity claims (applies to HTTP and direct calls; security is off in the local profile). */
@TestConfiguration(proxyBeanMethods = false)
class TestActors {

    static final Actor SUPER_ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));

    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(SUPER_ADMIN);
    private static final AtomicReference<CallerProfile> PROFILE = new AtomicReference<>(CallerProfile.empty());

    static void as(Actor actor) {
        CURRENT.set(actor);
    }

    static void profile(CallerProfile profile) {
        PROFILE.set(profile);
    }

    static void reset() {
        CURRENT.set(SUPER_ADMIN);
        PROFILE.set(CallerProfile.empty());
    }

    /** A signed-in customer who starts selling (token carries no org yet). */
    static Actor applicant(String userId) {
        return new Actor(userId, PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer",
                "account.seller_applicant"), Set.of());
    }

    static Actor member(String userId, String orgId, String role) {
        return new Actor(userId, PrincipalType.SELLER_MEMBER, Optional.of(orgId), Set.of(role), Set.of());
    }

    static Actor staff(String userId, String... permissions) {
        return new Actor(userId, PrincipalType.STAFF, Optional.empty(), Set.of("trust.reviewer"),
                Set.of(permissions));
    }

    @Bean
    ActorResolver switchableActorResolver() {
        return CURRENT::get;
    }

    @Bean
    @Primary
    CallerProfileProvider switchableCallerProfile() {
        return PROFILE::get;
    }
}
