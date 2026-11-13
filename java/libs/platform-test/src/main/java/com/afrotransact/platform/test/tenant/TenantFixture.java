package com.afrotransact.platform.test.tenant;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.DefaultPermissionChecker;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessCheckers;

/**
 * Two tenants (org A, org B), two customers and the platform actors, plus a switchable {@link ActorResolver} that
 * wires the real RBAC checkers, so a unit test can call an application service as tenant A against tenant B's ids.
 */
public final class TenantFixture {

    public static final String ORG_A = "org-a";
    public static final String ORG_B = "org-b";
    public static final String CUSTOMER_1 = "cust-1";
    public static final String CUSTOMER_2 = "cust-2";

    private final AtomicReference<Actor> current = new AtomicReference<>(staff("*"));

    /** The resolver to inject into the service under test. */
    public ActorResolver resolver() {
        return current::get;
    }

    public PermissionChecker permissions() {
        return new DefaultPermissionChecker(resolver());
    }

    /** The production RBAC-only checker (FGA off), as prod runs it. */
    public ResourceAccessChecker access() {
        return ResourceAccessCheckers.rbacOnly(permissions());
    }

    public TenantFixture as(Actor actor) {
        current.set(actor);
        return this;
    }

    public static Actor sellerOf(String org, String... permissions) {
        return new Actor("user-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of(permissions));
    }

    public static Actor providerOf(String org, String... permissions) {
        return new Actor("prov-" + org, PrincipalType.PROVIDER_MEMBER, Optional.of(org), Set.of("provider.owner"),
                Set.of(permissions));
    }

    public static Actor customer(String id, String... permissions) {
        return new Actor(id, PrincipalType.CUSTOMER, Optional.empty(), Set.of("customer"), Set.of(permissions));
    }

    public static Actor staff(String... permissions) {
        return new Actor("ops-user", PrincipalType.STAFF, Optional.empty(), Set.of("ops.manager"),
                Set.of(permissions));
    }

    public static Actor service(String... permissions) {
        return new Actor("svc-test", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of(permissions));
    }

    public static Actor driver(String id, String... permissions) {
        return new Actor(id, PrincipalType.DRIVER, Optional.empty(), Set.of("driver"), Set.of(permissions));
    }
}
