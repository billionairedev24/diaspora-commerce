package com.afrotransact.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.payments.application.MembershipBenefitsService;
import com.afrotransact.payments.application.MembershipGrants;
import com.afrotransact.payments.application.MembershipService;
import com.afrotransact.payments.application.MembershipTierService;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;

/** F14: the gateway-bff reads the published tiers for a signed-out visitor with ONE minimal scope, never a scope-less token. */
class MembershipOfferScopeTest {

    private static MembershipController controller(Actor actor) {
        MembershipTierService tiers = mock(MembershipTierService.class);
        when(tiers.published()).thenReturn(List.of());
        ActorResolver actors = () -> actor;
        return new MembershipController(tiers, mock(MembershipService.class), mock(MembershipBenefitsService.class),
                mock(MembershipGrants.class), actors);
    }

    private static Actor service(String... perms) {
        return new Actor("gateway-bff", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of(perms));
    }

    @Test
    void aServiceClientWithTheOfferScopeReadsTheTiers() {
        assertThat(controller(service("payments:membership-offer.read")).publishedTiers()).isEmpty();
    }

    @Test
    void aServiceClientWithoutTheScopeIsRefused() {
        assertThatThrownBy(() -> controller(service("payments:fees.read", "tax:quote")).publishedTiers())
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void aSignedInShopperIsUnchanged() {
        Actor shopper = new Actor("u1", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of());

        assertThat(controller(shopper).publishedTiers()).isEmpty();
    }
}
