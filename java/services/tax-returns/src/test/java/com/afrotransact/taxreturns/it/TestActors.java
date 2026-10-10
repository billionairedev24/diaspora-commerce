package com.afrotransact.taxreturns.it;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;

/** Switchable actor for integration tests (replaces the local super-admin resolver). */
final class TestActors implements ActorResolver {

    private volatile Actor actor = staffActor();

    private static Actor staffActor() {
        return new Actor("staff_1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.super_admin"),
                Set.of("*"));
    }

    @Override
    public Actor current() {
        return actor;
    }

    void staff() {
        actor = staffActor();
    }

    void customer(String subject) {
        actor = new Actor(subject, PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer"), Set.of());
    }

    void seller(String orgId, String... permissions) {
        actor = new Actor("member_" + orgId, PrincipalType.SELLER_MEMBER, Optional.of(orgId), Set.of("seller.owner"),
                Set.of(permissions));
    }

    void driver() {
        actor = new Actor("driver_1", PrincipalType.DRIVER, Optional.empty(), Set.of("platform.driver"),
                Set.of("delivery:operate"));
    }

    void service(String... permissions) {
        actor = new Actor("orders-service", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of(permissions));
    }
}
