package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.afrotransact.catalog.api.CatalogLookupApi;
import com.afrotransact.catalog.api.LabelApi;
import com.afrotransact.content.api.AdSpendApi;
import com.afrotransact.identity.api.StaffDirectoryApi;
import com.afrotransact.inventory.api.ReturnsApi;
import com.afrotransact.inventory.api.StorageUsageApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.offers.api.OffersApi;
import com.afrotransact.orders.api.OrderLookupApi;
import com.afrotransact.payments.api.ReturnRefundApi;
import com.afrotransact.payments.api.SellerBillingApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.seller.api.SellerLookupApi;
import com.afrotransact.taxreturns.api.ListingDecisionApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Boots afrotransact-core on a real PostgreSQL (the same environment as {@link CoreBootTest}: {@code AT_TEST_PG_ADMIN_URL},
 * {@code AT_TEST_PG_ADMIN_USER}, {@code AT_TEST_PG_ADMIN_PASSWORD}; {@code AT_TEST_CORE_FULL=true} also starts orders, fulfilment and
 * ai-gateway) and proves two things about the in-process module edges (ADR 0011 points 4 and 5, success criterion S6):
 *
 * <ul>
 *   <li><b>the chosen adapter</b>: every in-process adapter of an edge whose callee is running is a bean, and no module-to-module
 *   HTTP adapter is a bean in core unless its edge is listed in {@code http-edges.txt} or its callee is switched off;</li>
 *   <li><b>authorization parity</b>: each published API method refuses a caller that lacks the scope its HTTP endpoint needs with
 *   status 403, and does not refuse one that has it. One parametrised case per method.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "AT_TEST_PG_ADMIN_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModuleEdgesInCoreTest {

    private static final boolean FULL = "true".equals(System.getenv("AT_TEST_CORE_FULL"));
    private static final Set<String> OFF = FULL ? Set.of() : Set.of("orders", "fulfilment", "ai-gateway", "mcp-server");

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
    void everyInProcessAdapterOfARunningCalleeIsTheBeanAndNoUnlistedHttpAdapterIs() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> wrong = new ArrayList<>();
        int inProcessChecked = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.afrotransact")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            ConditionalOnModuleMode edge = type.getAnnotation(ConditionalOnModuleMode.class);
            if (edge == null) {
                continue;
            }
            boolean calleeOff = OFF.contains(edge.callee());
            int beans = ctx.getBeanNamesForType(type).length;
            if (edge.value() == ModuleMode.IN_PROCESS) {
                inProcessChecked++;
                if (beans != (calleeOff ? 0 : 1)) {
                    wrong.add(type.getSimpleName() + " (" + edge.caller() + " -> " + edge.callee() + "): " + beans
                            + " beans, callee " + (calleeOff ? "off" : "running"));
                }
            } else if (beans != (calleeOff ? 1 : 0)) {
                wrong.add(type.getSimpleName() + " (" + edge.caller() + " -> " + edge.callee() + "): HTTP adapter has "
                        + beans + " beans, callee " + (calleeOff ? "off" : "running"));
            }
        }
        assertThat(inProcessChecked).as("in-process adapters found").isGreaterThan(10);
        assertThat(wrong).as("edges whose chosen adapter is not the one the mode asks for").isEmpty();

        // Beans declared by @Bean methods are not components: look at every bean of an HTTP adapter class.
        Map<String, String> staysHttp = ModuleEdgeGuardTest.allowList();
        List<String> unlisted = new ArrayList<>();
        for (String name : ctx.getBeanDefinitionNames()) {
            Class<?> type = ctx.getType(name);
            if (type == null || !type.getPackageName().contains(".adapter.out.") || !type.getSimpleName().startsWith("Http")
                    || type.isAnnotationPresent(ConditionalOnModuleMode.class)) {
                continue;
            }
            if (!staysHttp.containsKey(type.getSimpleName())) {
                unlisted.add(type.getName());
            }
        }
        assertThat(unlisted).as("HTTP adapters selected in core that are not on the stay-HTTP list").isEmpty();
    }

    // ---------------------------------------------------------------- S6: one parametrised case per API method

    /** One published API method: the module it lives in, the scope its HTTP endpoint needs, and how to call it. */
    private record Method(String name, String module, String scope, Consumer<ConfigurableApplicationContext> call) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Method method(String name, String module, String scope, Consumer<ConfigurableApplicationContext> call) {
        return new Method(name, module, scope, call);
    }

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    static Stream<Arguments> apiMethods() {
        OffersApi.OfferTerms terms = new OffersApi.OfferTerms("r1", "AT00000000", "SKU-1", "NEW", 100L, null, null,
                "SELLER", 1, 1, null, null, null);
        List<Method> methods = List.of(
                method("OffersApi.upsert", "offers", "offers:write",
                        c -> c.getBean(OffersApi.class).upsert("org_x", null, null, List.of(terms))),
                method("OffersApi.setPricesAndStock", "offers", "offers:write", c -> c.getBean(OffersApi.class)
                        .setPricesAndStock("org_x", null, null, List.of(new OffersApi.PriceStock("r1", "SKU-1", null, 100L,
                                null, null, null)))),
                method("OffersApi.hasOpenOffer", "offers", "offers:read",
                        c -> c.getBean(OffersApi.class).hasOpenOffer("org_x", "AT00000000")),
                method("CatalogLookupApi.liveVariantByGtin", "catalog", "catalog:read",
                        c -> c.getBean(CatalogLookupApi.class).liveVariantByGtin("00012345678905")),
                method("CatalogLookupApi.offerEligibility", "catalog", "catalog:read",
                        c -> c.getBean(CatalogLookupApi.class).offerEligibility("AT00000000", "org_x")),
                method("CatalogLookupApi.bakeryClaimOrganisations", "catalog", "catalog:bakery-claims.read",
                        c -> c.getBean(CatalogLookupApi.class).bakeryClaimOrganisations()),
                method("LabelApi.unitLabel", "catalog", "catalog:read",
                        c -> c.getBean(LabelApi.class).unitLabel("AT00000000", "org_x")),
                method("LabelApi.renderBatch", "catalog", "catalog:read", c -> c.getBean(LabelApi.class).renderBatch(
                        List.of(new LabelApi.Label("UNIT", "ATF3XK8P2QL", null, null, List.of(), 1, null)), "PDF")),
                method("SellerLookupApi.sellingStatus", "seller", "sellers:catalog-scope.read",
                        c -> c.getBean(SellerLookupApi.class).sellingStatus("org_x", null, null, null, null, null)),
                method("SellerLookupApi.listingEntitlements", "seller", "sellers:entitlements.read",
                        c -> c.getBean(SellerLookupApi.class).listingEntitlements("org_x")),
                method("SellerLookupApi.shipFromLocation", "seller", "sellers:ship-from.read",
                        c -> c.getBean(SellerLookupApi.class).shipFromLocation(UUID.randomUUID().toString())),
                method("ListingDecisionApi.find", "tax-returns", "tax:read",
                        c -> c.getBean(ListingDecisionApi.class).find("AT00000000", "org_x")),
                method("ListingDecisionApi.request", "tax-returns", "tax:classify",
                        c -> c.getBean(ListingDecisionApi.class).request("AT00000000", "org_x", Map.of())),
                method("StaffDirectoryApi.staff", "identity", "users:read",
                        c -> c.getBean(StaffDirectoryApi.class).staff("catalog:moderate", null, null)),
                method("AdSpendApi.spend", "content", "ads:spend.read",
                        c -> c.getBean(AdSpendApi.class).spend(DAY, DAY)),
                method("StorageUsageApi.usage", "inventory", "inventory:storage.read",
                        c -> c.getBean(StorageUsageApi.class).usage(DAY, DAY)),
                method("ReturnsApi.acceptReturn", "inventory", "inventory:adjust",
                        c -> c.getBean(ReturnsApi.class).acceptReturn("order-1", "SKU-1", 1, "RESTOCK", "test", null, "k1")),
                method("ReturnRefundApi.paymentForOrder", "payments", "payments:service",
                        c -> c.getBean(ReturnRefundApi.class).paymentForOrder(UUID.randomUUID())),
                method("ReturnRefundApi.refund", "payments", "payments:service", c -> c.getBean(ReturnRefundApi.class)
                        .refund(UUID.randomUUID(), List.of(), "test", "k1")),
                method("SellerBillingApi.registerConnectedAccount", "payments", "payments:connected-accounts",
                        c -> c.getBean(SellerBillingApi.class).registerConnectedAccount("org_x", "acct_x")),
                method("SellerBillingApi.withhold", "payments", "payments:plan-fees",
                        c -> c.getBean(SellerBillingApi.class).withhold("org_x", "inv-1", "N-1", 100L, "USD", "plan-fee-inv-1")),
                method("SellerBillingApi.release", "payments", "payments:plan-fees",
                        c -> c.getBean(SellerBillingApi.class).release("org_x", "inv-1", 100L, "test", "plan-fee-release-1")),
                method("OrderLookupApi.lookup", "orders", "orders:lookup",
                        c -> c.getBean(OrderLookupApi.class).lookup(UUID.randomUUID().toString())),
                method("OrderLookupApi.purchase", "orders", "orders:purchase-check",
                        c -> c.getBean(OrderLookupApi.class).purchase("cust-1", "AT00000000")));
        return methods.stream().map(m -> Arguments.of(m));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiMethods")
    void aCallerWithoutTheScopeIsRefusedWith403AndOneWithItIsNot(Method m) {
        assumeTrue(!OFF.contains(m.module()), "module " + m.module() + " is not started in this run");
        CallerPrincipal without = new CallerPrincipal("scope-test", "scope-test-service", Set.of("unrelated:scope"));
        assertThatThrownBy(() -> InProcessCalls.as(without, () -> {
            m.call().accept(ctx);
            return null;
        })).as("%s without %s", m.name(), m.scope()).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).as("status of %s", m.name()).isEqualTo(403));
        CallerPrincipal with = new CallerPrincipal("scope-test", "scope-test-service", Set.of(m.scope()));
        try {
            InProcessCalls.as(with, () -> {
                m.call().accept(ctx);
                return null;
            });
        } catch (ModuleApiException e) {
            assertThat(e.status()).as("%s with %s must get past the scope check (got %s)", m.name(), m.scope(), e.getMessage())
                    .isNotEqualTo(403);
        } catch (RuntimeException e) {
            // any other outcome (a rule, an unknown id, a downstream refusal) means the scope check passed
        }
    }
}
