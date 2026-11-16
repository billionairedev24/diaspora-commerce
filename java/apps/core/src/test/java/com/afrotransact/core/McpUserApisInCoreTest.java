package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.afrotransact.bookings.api.ServiceSearchApi;
import com.afrotransact.catalog.api.CatalogUserApi;
import com.afrotransact.inventory.api.InventoryUserApi;
import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.payments.api.PayoutReadApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.seller.api.SellerUserApi;
import com.afrotransact.taxreturns.api.TaxReviewReadApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * mcp-server's tool edges in a running core (real application services on PostgreSQL, same environment as {@link CoreBootTest}; orders,
 * fulfilment and ai-gateway are not started here, so the orders edge keeps its HTTP adapter and is not exercised):
 *
 * <ul>
 *   <li>every mcp-server edge whose callee runs is the in-process adapter and not the HTTP one;</li>
 *   <li>the calling module needs its delegation scope for each API ({@code 403 auth.missing-permission} without it);</li>
 *   <li>the call runs AS the signed-in person and never widens what that person may do: a person without the permission the
 *   HTTP endpoint needs is refused by the real use case even when mcp-server's own principal also holds that permission as a scope,
 *   and a person who holds it gets past the check.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "AT_TEST_PG_ADMIN_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpUserApisInCoreTest {

    private static final Set<String> OFF = Set.of("orders", "fulfilment", "ai-gateway", "delivery-runs", "retail");

    private ConfigurableApplicationContext ctx;

    @BeforeAll
    void start() {
        String url = System.getenv("AT_TEST_PG_ADMIN_URL");
        Map<String, Object> properties = new HashMap<>(Map.of(
                "server.port", "0",
                "spring.profiles.active", "local",
                "at.consumers.enabled", "false",
                "CORE_DB_URL", url,
                "at.modules.schema-admin.url", url,
                "at.modules.schema-admin.username", System.getenv("AT_TEST_PG_ADMIN_USER"),
                "at.modules.schema-admin.password", System.getenv("AT_TEST_PG_ADMIN_PASSWORD")));
        for (String off : OFF) {
            properties.put("at.modules." + off + ".enabled", "false");
        }
        ctx = new SpringApplicationBuilder(CoreApplication.class)
                .run(properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    @AfterAll
    void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    void everyMcpServerEdgeOfARunningCalleeIsTheInProcessAdapter() throws Exception {
        int inProcess = 0;
        for (Class<?> type : ModuleEdgeGuardTest.components()) {
            ConditionalOnModuleMode edge = type.getAnnotation(ConditionalOnModuleMode.class);
            if (edge == null || !"mcp-server".equals(edge.caller())) {
                continue;
            }
            boolean calleeOff = OFF.contains(edge.callee());
            int beans = ctx.getBeanNamesForType(type).length;
            if (edge.value() == ModuleMode.IN_PROCESS) {
                inProcess++;
                assertThat(beans).as("%s (mcp-server -> %s)", type.getSimpleName(), edge.callee()).isEqualTo(calleeOff ? 0 : 1);
            } else {
                assertThat(beans).as("HTTP %s (mcp-server -> %s)", type.getSimpleName(), edge.callee())
                        .isEqualTo(calleeOff ? 1 : 0);
            }
        }
        assertThat(inProcess).as("in-process mcp-server adapters found").isEqualTo(8);
        // and the ports the tools use really are those adapters, serving a person
        assertThat(ctx.getBean(CatalogApi.class).getClass().getSimpleName()).isEqualTo("InProcessCatalogApi");
        assertThat(ctx.getBean(InventoryApi.class).getClass().getSimpleName()).isEqualTo("InProcessInventoryApi");
    }

    /** One API method: the module, the permission its HTTP endpoint needs, whether it needs one, and how to call it. */
    private record Case(String name, String module, String delegation, String permission, Function<ConfigurableApplicationContext,
            Function<EndUserActor, Object>> call) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                new Case("CatalogUserApi.categoryRequests", "catalog", CatalogUserApi.READ_SCOPE, "catalog:taxonomy",
                        c -> u -> c.getBean(CatalogUserApi.class).categoryRequests(u, null)),
                new Case("InventoryUserApi.positions", "inventory", InventoryUserApi.READ_SCOPE, "inventory:read",
                        c -> u -> c.getBean(InventoryUserApi.class).positions(u, "org_x", null, false)),
                new Case("PayoutReadApi.balance", "payments", PayoutReadApi.READ_SCOPE, "payments:read",
                        c -> u -> c.getBean(PayoutReadApi.class).balance(u, "org_x")),
                new Case("SellerUserApi.applicationQueue", "seller", SellerUserApi.READ_SCOPE, "applications:review",
                        c -> u -> c.getBean(SellerUserApi.class).applicationQueue(u, "PENDING_REVIEW", 0, 25)),
                new Case("TaxReviewReadApi.reviewQueue", "tax-returns", TaxReviewReadApi.READ_SCOPE, "tax:read",
                        c -> u -> c.getBean(TaxReviewReadApi.class).reviewQueue(u, "PENDING_REVIEW", null, 0, 25))).map(Arguments::of);
    }

    private static EndUserActor person(String type, String... permissions) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(type.toLowerCase() + "-1").claim("pt", type)
                .claim("perms", List.of(permissions)).build();
        return EndUserActor.fromAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    private Object run(CallerPrincipal caller, Case c, EndUserActor user) {
        return InProcessCalls.as(caller, c.module(), () -> c.call().apply(ctx).apply(user));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theModuleNeedsItsDelegationScopeThePersonIsTheActorAndNothingIsWidened(Case c) {
        EndUserActor powerless = person("CUSTOMER");
        EndUserActor entitled = person("STAFF", c.permission());

        // 1. a module without the delegation scope is refused before anything runs
        CallerPrincipal noDelegation = new CallerPrincipal("mcp-server", "mcp-server-service", Set.of(c.permission()));
        assertThatThrownBy(() -> run(noDelegation, c, entitled)).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("auth.missing-permission");
            assertThat(e.getMessage()).contains(c.delegation());
        });

        // 2. mcp-server's own scopes never become the person's: even holding the permission as a scope itself, the call for a person
        //    who lacks it is refused by the real use case, as their own token would be over HTTP
        CallerPrincipal generous = new CallerPrincipal("mcp-server", "mcp-server-service", Set.of(c.delegation(), c.permission()));
        assertThatThrownBy(() -> run(generous, c, powerless)).as("%s for a person without %s", c.name(), c.permission())
                .isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(403));

        // 3. a person who holds the permission gets past the check (what follows is the use case's own business)
        CallerPrincipal delegated = new CallerPrincipal("mcp-server", "mcp-server-service", Set.of(c.delegation()));
        try {
            run(delegated, c, entitled);
        } catch (ModuleApiException e) {
            assertThat(e.status()).as("%s for a person with %s (%s)", c.name(), c.permission(), e.getMessage()).isNotEqualTo(403);
        } catch (RuntimeException e) {
            // any other outcome (an empty page, a rule, an unknown id) means the permission check passed
        }
    }

    @Test
    void thePublicServiceSearchRunsForAnyPersonWithTheDelegationScope() {
        CallerPrincipal delegated = new CallerPrincipal("mcp-server", "mcp-server-service", Set.of(ServiceSearchApi.READ_SCOPE));
        try {
            Object result = InProcessCalls.as(delegated, "bookings",
                    () -> ctx.getBean(ServiceSearchApi.class).findServices(person("CUSTOMER"), "braids", null));
            assertThat(result).isNotNull();
        } catch (ModuleApiException e) {
            assertThat(e.status()).as(e.getMessage()).isNotEqualTo(403);
        }
        assertThatThrownBy(() -> InProcessCalls.as(new CallerPrincipal("mcp-server", "mcp-server-service", Set.of("x:y")), "bookings",
                () -> ctx.getBean(ServiceSearchApi.class).findServices(person("CUSTOMER"), "braids", null)))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void aToolCallEntersTheCalleeAsThePersonThroughThePort() {
        // the real mcp-server port, the real catalog: a customer's request for the staff-only queue is a 403 the tool maps to FORBIDDEN
        CallerCredentials customer = new CallerCredentials("ignored", () -> person("CUSTOMER", "mcp:shopper"));
        assertThat(ctx.getBean(CatalogApi.class).categoryRequests(null, customer)).isEqualTo(Downstream.of(Downstream.Status.FORBIDDEN));
    }
}
