package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A person who signed up as a customer and then had a seller application approved holds a token with
 * {@code pt=SELLER_MEMBER} and the organisation. On the storefront (X-Acting-As: CUSTOMER) they still shop as
 * themselves; in Seller Center (no header) the organisation context stays.
 */
class ActingAsActorResolverTest {

    private static final Map<String, Object> DUAL_ROLE = Map.of("sub", "user-1", "pt", "SELLER_MEMBER",
            "org", "org_malo", "roles", List.of("account.customer", "account.seller_applicant", "seller.owner"),
            "perms", List.of("catalog:write", "mcp:shopper"));

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private static void request(String actingAs) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers/me/payment-methods");
        if (actingAs != null) {
            request.addHeader(ActingAsActorResolver.HEADER, actingAs);
        }
        request.addHeader("Authorization", "Bearer " + unsignedToken());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static String unsignedToken() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"user-1\",\"pt\":\"SELLER_MEMBER\",\"org\":\"org_malo\","
                + "\"roles\":[\"account.customer\",\"seller.owner\"],\"perms\":[\"catalog:write\"]}";
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
    }

    private static void signedIn(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void aSellerMemberOnTheStorefrontShopsAsThemselves() {
        signedIn(DUAL_ROLE);
        request("CUSTOMER");

        Actor actor = new ActingAsActorResolver(new JwtActorResolver()).current();

        assertThat(actor.subject()).isEqualTo("user-1");
        assertThat(actor.type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(actor.orgId()).isEmpty();
        assertThat(actor.roles()).containsExactlyInAnyOrder("account.customer", "account.seller_applicant");
        assertThat(actor.permissions()).containsExactlyInAnyOrder("catalog:write", "mcp:shopper");
    }

    @Test
    void sellerCenterKeepsTheOrganisationContext() {
        signedIn(DUAL_ROLE);
        request(null);

        Actor actor = new ActingAsActorResolver(new JwtActorResolver()).current();

        assertThat(actor.type()).isEqualTo(PrincipalType.SELLER_MEMBER);
        assertThat(actor.orgId()).contains("org_malo");
        assertThat(actor.roles()).contains("seller.owner");
    }

    @Test
    void theLocalProfileAppliesTheContextToo() {
        request("customer");

        Actor actor = new ActingAsActorResolver(new LocalActorResolver()).current();

        assertThat(actor.subject()).isEqualTo("user-1");
        assertThat(actor.type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(actor.orgId()).isEmpty();
    }

    @Test
    void staffServicesAndTheLocalSuperAdminNeverBecomeShoppers() {
        Actor staff = new Actor("s1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.ops_manager"),
                Set.of("orders:read"));
        Actor service = new Actor("orders", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of("cart:service"));

        assertThat(ActingAsActorResolver.actingAs(staff, "CUSTOMER")).isEqualTo(staff);
        assertThat(ActingAsActorResolver.actingAs(service, "CUSTOMER")).isEqualTo(service);
        assertThat(ActingAsActorResolver.actingAs(LocalActorResolver.LOCAL_SUPER_ADMIN, "CUSTOMER"))
                .isEqualTo(LocalActorResolver.LOCAL_SUPER_ADMIN);
    }

    @Test
    void unknownOrMissingContextsLeaveTheActorAlone() {
        Actor member = new Actor("user-1", PrincipalType.PROVIDER_MEMBER, Optional.of("org_p"),
                Set.of("provider.owner"), Set.of());

        assertThat(ActingAsActorResolver.actingAs(member, null)).isEqualTo(member);
        assertThat(ActingAsActorResolver.actingAs(member, " ")).isEqualTo(member);
        assertThat(ActingAsActorResolver.actingAs(member, "SELLER_MEMBER")).isEqualTo(member);
        assertThat(ActingAsActorResolver.actingAs(member, "customer").type()).isEqualTo(PrincipalType.CUSTOMER);
    }

    @Test
    void outsideARequestTheDelegateDecides() {
        signedIn(DUAL_ROLE);

        assertThat(new ActingAsActorResolver(new JwtActorResolver()).current().type())
                .isEqualTo(PrincipalType.SELLER_MEMBER);
    }

    @Test
    void theAutoConfiguredResolverAppliesTheActingContext() {
        PlatformSecurityAutoConfiguration config = new PlatformSecurityAutoConfiguration();
        signedIn(DUAL_ROLE);
        request("CUSTOMER");

        assertThat(config.actorResolver(new PlatformSecurityProperties(true, List.of(), List.of(), List.of()))
                .current().type()).isEqualTo(PrincipalType.CUSTOMER);
    }
}
