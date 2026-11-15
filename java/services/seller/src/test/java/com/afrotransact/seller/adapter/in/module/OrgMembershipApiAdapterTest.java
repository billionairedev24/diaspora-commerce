package com.afrotransact.seller.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.seller.application.OrgMembershipService;
import org.junit.jupiter.api.Test;

/** The in-process membership entry fails closed without a caller, and only delegates (the service checks the scope). */
class OrgMembershipApiAdapterTest {

    private static final CallerPrincipal PAYMENTS = new CallerPrincipal("payments", "payments-service",
            Set.of("sellers:membership.read"));

    private static ActorResolver actorOf(String subject) {
        return () -> new Actor(subject, PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of());
    }

    private static final OrgMembershipService ROLES = new OrgMembershipService() {
        @Override
        public Optional<String> roleOf(String orgId, String userId) {
            return "u1".equals(userId) ? Optional.of("seller.owner") : Optional.empty();
        }
    };

    @Test
    void refusesToRunWithoutACaller() {
        OrgMembershipApiAdapter adapter = new OrgMembershipApiAdapter(ROLES, actorOf("payments-service"));
        assertThatThrownBy(() -> adapter.roleOf("org", "u1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a caller principal");
    }

    @Test
    void refusesWhenTheActorInForceIsNotTheCaller() {
        OrgMembershipApiAdapter adapter = new OrgMembershipApiAdapter(ROLES, actorOf("someone-else"));
        assertThatThrownBy(() -> InProcessCalls.as(PAYMENTS, () -> adapter.roleOf("org", "u1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not the in-process caller");
    }

    @Test
    void answersLikeTheHttpEndpointForTheCaller() {
        OrgMembershipApiAdapter adapter = new OrgMembershipApiAdapter(ROLES, actorOf("payments-service"));
        assertThat(InProcessCalls.as(PAYMENTS, () -> adapter.roleOf("org", "u1"))).contains("seller.owner");
        assertThat(InProcessCalls.as(PAYMENTS, () -> adapter.roleOf("org", "nobody"))).isEmpty();
    }
}
