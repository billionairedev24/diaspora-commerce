package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The end-user actor: built only from a validated JWT authentication, exactly as the token says, never a service, narrowed to a
 * shopper the way {@code X-Acting-As: CUSTOMER} narrows, and the only actor in force inside a call entered with
 * {@link ModuleEntry#runAsUser}.
 */
class EndUserActorTest {

    private static final String ADDRESS_RELAY = "identity:addresses.read";
    private static final Map<String, Object> DUAL_ROLE = Map.of("sub", "user-1", "pt", "SELLER_MEMBER", "org", "org_malo",
            "roles", List.of("account.customer", "account.seller_applicant", "seller.owner"),
            "perms", List.of("catalog:write", "mcp:shopper"), "scope", "openid orders:read");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static JwtAuthenticationToken token(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
    }

    private static CallerPrincipal caller(String... scopes) {
        return new CallerPrincipal("orders", "orders-service", Set.of(scopes));
    }

    private static final ActorResolver IN_FORCE = new ActorResolver() {
        @Override
        public Actor current() {
            return InProcessCalls.currentUser()
                    .map(u -> new Actor(u.userId(), PrincipalType.valueOf(u.principalType()), u.organisationId(), u.roles(),
                            u.scopes()))
                    .orElse(new Actor("orders-service", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of()));
        }
    };

    @Test
    void carriesWhatTheValidatedTokenCarries() {
        EndUserActor actor = EndUserActor.fromAuthentication(token(DUAL_ROLE));
        assertThat(actor.userId()).isEqualTo("user-1");
        assertThat(actor.principalType()).isEqualTo("SELLER_MEMBER");
        assertThat(actor.organisationId()).contains("org_malo");
        assertThat(actor.organisationIds()).containsExactly("org_malo");
        assertThat(actor.roles()).containsExactlyInAnyOrder("account.customer", "account.seller_applicant", "seller.owner");
        assertThat(actor.scopes()).containsExactlyInAnyOrder("catalog:write", "mcp:shopper", "openid", "orders:read");
    }

    @Test
    void matchesWhatTheHttpPathResolves() {
        SecurityContextHolder.getContext().setAuthentication(token(DUAL_ROLE));
        Actor http = new JwtActorResolver().current();
        EndUserActor actor = EndUserActor.fromAuthentication(SecurityContextHolder.getContext().getAuthentication());
        assertThat(actor.userId()).isEqualTo(http.subject());
        assertThat(actor.principalType()).isEqualTo(http.type().name());
        assertThat(actor.organisationId()).isEqualTo(http.orgId());
        assertThat(actor.roles()).isEqualTo(http.roles());
        assertThat(actor.scopes()).isEqualTo(http.permissions());
    }

    @Test
    void asCustomerNarrowsExactlyLikeActingAs() {
        for (String type : List.of("CUSTOMER", "SELLER_MEMBER", "PROVIDER_MEMBER", "DRIVER", "STAFF", "AI_AGENT", "PARTNER_APP")) {
            Map<String, Object> claims = Map.of("sub", "u", "pt", type, "org", "o1", "roles",
                    List.of("account.customer", "seller.owner"), "perms", List.of("catalog:write", "mcp:shopper"));
            SecurityContextHolder.getContext().setAuthentication(token(claims));
            Actor viaHeader = ActingAsActorResolver.actingAs(new JwtActorResolver().current(), "CUSTOMER");
            EndUserActor narrowed = EndUserActor.fromAuthentication(token(claims)).asCustomer();
            assertThat(narrowed.principalType()).as(type).isEqualTo(viaHeader.type().name());
            assertThat(narrowed.organisationId()).as(type).isEqualTo(viaHeader.orgId());
            assertThat(narrowed.roles()).as(type).isEqualTo(viaHeader.roles());
            assertThat(narrowed.scopes()).as(type).isEqualTo(viaHeader.permissions());
        }
    }

    @Test
    void refusesAnythingThatIsNotAnAuthenticatedJwt() {
        assertThatThrownBy(() -> EndUserActor.fromAuthentication(null)).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(401));
        assertThatThrownBy(() -> EndUserActor.fromAuthentication(new AnonymousAuthenticationToken("k", "anon",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isInstanceOf(ModuleApiException.class);
        assertThatThrownBy(() -> EndUserActor.fromAuthentication(new TestingAuthenticationToken("user-1", "x", "ROLE_USER")))
                .isInstanceOf(ModuleApiException.class);
        JwtAuthenticationToken unauthenticated = token(DUAL_ROLE);
        unauthenticated.setAuthenticated(false);
        assertThatThrownBy(() -> EndUserActor.fromAuthentication(unauthenticated)).isInstanceOf(ModuleApiException.class);
        assertThatThrownBy(() -> EndUserActor.fromAuthentication(token(Map.of("pt", "CUSTOMER", "sub", " "))))
                .isInstanceOf(ModuleApiException.class);
    }

    @Test
    void aServiceTokenIsNeverAnEndUser() {
        assertThatThrownBy(() -> EndUserActor.fromAuthentication(token(Map.of("sub", "orders-service", "pt", "SERVICE",
                "scope", "inventory:reserve")))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.service-token");
                });
    }

    @Test
    void thereIsNoOtherWayToBuildOne() {
        // sealed, one package-private implementation: nothing outside com.afrotransact.module can name it or implement the interface
        assertThat(EndUserActor.class.isSealed()).isTrue();
        assertThat(EndUserActor.class.getPermittedSubclasses()).hasSize(1);
        Class<?> impl = EndUserActor.class.getPermittedSubclasses()[0];
        assertThat(Modifier.isPublic(impl.getModifiers())).isFalse();
        assertThat(impl.getDeclaredConstructors()).allMatch(c -> !Modifier.isPublic(c.getModifiers()));
    }

    @Test
    void requireScopeAndOrganisationRefuseWith403() {
        EndUserActor actor = EndUserActor.fromAuthentication(token(DUAL_ROLE));
        actor.requireScope("orders:read");
        actor.requireOrganisation("org_malo");
        assertThatThrownBy(() -> actor.requireScope("orders:manage")).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.code()).isEqualTo("auth.missing-permission"));
        assertThatThrownBy(() -> actor.requireOrganisation("org_other")).isInstanceOfSatisfying(ModuleApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.org-mismatch");
                });
        assertThatThrownBy(() -> actor.asCustomer().requireOrganisation("org_malo")).isInstanceOf(ModuleApiException.class);
        assertThatThrownBy(() -> actor.requireOrganisation(null)).isInstanceOf(ModuleApiException.class);
    }

    @Test
    void insideRunAsUserThePersonIsTheActorAndNeverAService() {
        ModuleEntry entry = new ModuleEntry(IN_FORCE);
        EndUserActor user = EndUserActor.fromAuthentication(token(DUAL_ROLE));
        AtomicReference<Actor> seen = new AtomicReference<>();
        InProcessCalls.as(caller(ADDRESS_RELAY, "inventory:reserve"), () -> entry.runAsUser(user, ADDRESS_RELAY, () -> {
            seen.set(IN_FORCE.current());
            return "ok";
        }));
        assertThat(seen.get().subject()).isEqualTo("user-1");
        assertThat(seen.get().type()).isEqualTo(PrincipalType.SELLER_MEMBER);
        assertThat(seen.get().permissions()).doesNotContain("inventory:reserve", ADDRESS_RELAY);
        assertThat(InProcessCalls.currentUser()).isEmpty();
    }

    @Test
    void aCallerWithoutTheDelegationScopeIsRefusedEvenForAGenuinePerson() {
        ModuleEntry entry = new ModuleEntry(IN_FORCE);
        EndUserActor user = EndUserActor.fromAuthentication(token(DUAL_ROLE));
        assertThatThrownBy(() -> InProcessCalls.as(caller("inventory:reserve"),
                () -> entry.runAsUser(user, ADDRESS_RELAY, () -> "never"))).isInstanceOfSatisfying(ModuleApiException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(403);
                            assertThat(e.code()).isEqualTo("auth.missing-permission");
                        });
    }

    @Test
    void entryWithoutACallerOrWithoutAPersonFailsClosed() {
        ModuleEntry entry = new ModuleEntry(IN_FORCE);
        EndUserActor user = EndUserActor.fromAuthentication(token(DUAL_ROLE));
        assertThatThrownBy(() -> entry.runAsUser(user, ADDRESS_RELAY, () -> "never")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InProcessCalls.as(caller(ADDRESS_RELAY), () -> entry.runAsUser(null, ADDRESS_RELAY, () -> "never")))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    void aResolverThatIgnoresThePersonFailsClosed() {
        ModuleEntry entry = new ModuleEntry(() -> new Actor("orders-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of(ADDRESS_RELAY)));
        EndUserActor user = EndUserActor.fromAuthentication(token(DUAL_ROLE));
        assertThatThrownBy(() -> InProcessCalls.as(caller(ADDRESS_RELAY), () -> entry.runAsUser(user, ADDRESS_RELAY, () -> "x")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not honour end-user actors");
    }

    @Test
    void endUsersReadsTheSecurityContextOnly() {
        EndUsers users = new EndUsers(true);
        assertThatThrownBy(users::current).isInstanceOf(ModuleApiException.class);
        // a header or a parameter on the request counts for nothing when security is on
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4IiwicHQiOiJTVEFGRiJ9.c2ln");
        request.addParameter("userId", "someone-else");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThatThrownBy(users::current).isInstanceOf(ModuleApiException.class);
        SecurityContextHolder.getContext().setAuthentication(token(DUAL_ROLE));
        assertThat(users.current().userId()).isEqualTo("user-1");
        assertThat(users.currentAsCustomer().organisationId()).isEmpty();
    }

    @Test
    void withSecurityOffTheLocalResolverDecidesAndTheSameDoorIsUsed() {
        EndUsers users = new EndUsers(false);
        assertThat(users.current().userId()).isEqualTo("local-dev");
        assertThat(users.current().principalType()).isEqualTo("STAFF");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJjdXN0LTEiLCJwdCI6IkNVU1RPTUVSIn0.c2ln");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(users.current().userId()).isEqualTo("cust-1");
        assertThat(users.current().principalType()).isEqualTo("CUSTOMER");
    }
}
