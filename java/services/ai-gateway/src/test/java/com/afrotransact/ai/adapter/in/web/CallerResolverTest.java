package com.afrotransact.ai.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.ai.application.Caller;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.EndUsers;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** A Lola turn captures the signed-in person on the request thread, as a shopper, and never a service. */
class CallerResolverTest {

    private final CallerResolver callers = new CallerResolver(() -> new Actor("customer-1", PrincipalType.CUSTOMER,
            Optional.empty(), Set.of(), Set.of("ai:use")), new EndUsers(true));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(Map<String, Object> claims) {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    void theShopperIsThePersonOfTheRequestWithTheirTokenAndTheTokenStringStaysForGoCallees() {
        signIn(Map.of("sub", "customer-1", "pt", "CUSTOMER", "perms", List.of("ai:use", "mcp:shopper")));
        request.addHeader("Authorization", "Bearer abc.def.ghi");
        Caller caller = callers.shopper(request);
        assertThat(caller.user()).isNotNull();
        assertThat(caller.user().userId()).isEqualTo("customer-1");
        assertThat(caller.user().principalType()).isEqualTo("CUSTOMER");
        assertThat(caller.bearerToken()).isEqualTo("abc.def.ghi");
        assertThat(caller.shopper().user()).isSameAs(caller.user());
    }

    @Test
    void aSellerMemberShopsAsThemselvesWithoutTheirOrganisation() {
        signIn(Map.of("sub", "customer-1", "pt", "SELLER_MEMBER", "org", "org_malo", "roles", List.of("account.customer", "seller.owner"),
                "perms", List.of("catalog:write", "ai:use")));
        Caller caller = callers.shopper(request);
        assertThat(caller.user().principalType()).isEqualTo("CUSTOMER");
        assertThat(caller.user().organisationId()).isEmpty();
        assertThat(caller.user().roles()).containsExactly("account.customer");
    }

    @Test
    void aServiceTokenGivesNoPersonAndNeverBecomesOne() {
        signIn(Map.of("sub", "media-service", "pt", "SERVICE", "scope", "ai:use"));
        assertThat(callers.shopper(request).user()).isNull();
    }

    @Test
    void noAuthenticationGivesNoPerson() {
        assertThat(callers.shopper(request).user()).isNull();
    }

    @Test
    void currentStaysWithoutAPersonForEveryOtherEndpoint() {
        signIn(Map.of("sub", "customer-1", "pt", "CUSTOMER"));
        assertThat(callers.current(request).user()).isNull();
    }
}
