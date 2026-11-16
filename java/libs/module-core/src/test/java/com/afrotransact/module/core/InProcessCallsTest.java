package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.LocalActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** The caller principal: bound for one call only, and the actor in force inside it is the calling module. */
class InProcessCallsTest {

    private static final CallerPrincipal CATALOG = new CallerPrincipal("catalog", "catalog-service",
            Set.of("tax:classify"));
    private static final CallerPrincipal OTHER = new CallerPrincipal("offers", "offers-service", Set.of("tax:read"));

    private final ActorResolver resolver = new CallerAwareActorResolver(new LocalActorResolver());

    @Test
    void theCallerIsBoundOnlyDuringTheCall() {
        assertThat(InProcessCalls.current()).isEmpty();
        assertThat(InProcessCalls.as(CATALOG, () -> InProcessCalls.require().module())).isEqualTo("catalog");
        assertThat(InProcessCalls.current()).isEmpty();
    }

    @Test
    void aCallWithoutACallerIsRefused() {
        assertThatThrownBy(InProcessCalls::require).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a caller principal");
    }

    @Test
    void aNestedCallRebindsAndRestoresTheOuterCaller() {
        String seen = InProcessCalls.as(CATALOG, () -> {
            String inner = InProcessCalls.as(OTHER, () -> InProcessCalls.require().module());
            return inner + ">" + InProcessCalls.require().module();
        });
        assertThat(seen).isEqualTo("offers>catalog");
    }

    @Test
    void theBindingIsNotInheritedByAThreadTheCalleeStarts() throws InterruptedException {
        AtomicReference<Boolean> bound = new AtomicReference<>();
        InProcessCalls.as(CATALOG, () -> {
            Thread thread = Thread.ofVirtual().start(() -> bound.set(InProcessCalls.current().isPresent()));
            try {
                thread.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertThat(bound.get()).isFalse();
    }

    @Test
    void insideACallTheActorIsTheServiceWithOnlyItsScopes() {
        Actor actor = InProcessCalls.as(CATALOG, resolver::current);
        assertThat(actor.type()).isEqualTo(PrincipalType.SERVICE);
        assertThat(actor.subject()).isEqualTo("catalog-service");
        assertThat(actor.can("tax:classify")).isTrue();
        assertThat(actor.can("tax:classify-admin")).isFalse();
        assertThat(actor.can("anything-else")).isFalse();
    }

    @Test
    void outsideACallTheNormalResolverApplies() {
        assertThat(resolver.current()).isEqualTo(LocalActorResolver.LOCAL_SUPER_ADMIN);
    }

    @Test
    void aFailureInsideTheCallStillEndsTheBinding() {
        assertThatThrownBy(() -> InProcessCalls.as(CATALOG, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(InProcessCalls.current()).isEmpty();
    }

    @Test
    void insideACallMadeForAPersonTheActorIsThePersonAndNeverTheServiceNorTheCallersScopes() {
        EndUserActor person = InProcessKit.user("user-1", "pt", "SELLER_MEMBER", "org", "org_a", "roles", List.of("account.customer"),
                "perms", List.of("orders:read"));
        Actor actor = InProcessCalls.as(CATALOG, () -> InProcessCalls.withUser(person, resolver::current));
        assertThat(actor.type()).isEqualTo(PrincipalType.SELLER_MEMBER);
        assertThat(actor.subject()).isEqualTo("user-1");
        assertThat(actor.orgId()).contains("org_a");
        assertThat(actor.permissions()).containsExactly("orders:read");
        assertThat(InProcessCalls.currentUser()).isEmpty();
    }

    @Test
    void aPersonBindingWithoutAnInProcessCallerIsIgnored() {
        EndUserActor person = InProcessKit.user("user-1", "pt", "CUSTOMER");
        Actor actor = InProcessCalls.withUser(person, resolver::current);
        assertThat(actor).isEqualTo(LocalActorResolver.LOCAL_SUPER_ADMIN);
    }
}
