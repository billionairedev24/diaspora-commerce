package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.afrotransact.catalog.adapter.out.module.InProcessTaxDecisionAuthority;
import com.afrotransact.catalog.adapter.out.module.InProcessTaxResolver;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.taxreturns.api.TaxClassificationApi;
import com.afrotransact.taxreturns.port.ProductFacts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;

/**
 * The spike's claims, proved on a real context and a real PostgreSQL (see {@link CoreRun}): both modules start with their own
 * database, call each other in-process under an explicit principal whose scopes are enforced, switch to HTTP by configuration,
 * and fail to start with a message that names both modules when the target module is off.
 */
class CoreModulesTest {

    private static TaxResolver.Request unknownCategory() {
        return new TaxResolver.Request("NO-SUCH-CATEGORY", null, Map.of(), Map.of());
    }

    @Test
    void eachModuleOwnsItsDatabaseAndEveryJdbcClientInAModuleIsThatModulesOwn() throws IllegalAccessException {
        try (ConfigurableApplicationContext ctx = CoreRun.start()) {
            assertThat(ctx.getBean("catalog.jdbcClient", JdbcClient.class).sql("select current_database()")
                    .query(String.class).single()).isEqualTo("catalog");
            assertThat(ctx.getBean("tax-returns.jdbcClient", JdbcClient.class).sql("select current_database()")
                    .query(String.class).single()).isEqualTo("taxreturns");
            int checked = 0;
            for (String name : ctx.getBeanDefinitionNames()) {
                Object bean;
                try {
                    bean = ctx.getBean(name);
                } catch (BeansException ex) {
                    continue;
                }
                Class<?> type = AopUtils.getTargetClass(bean);
                String module = type.getName().startsWith("com.afrotransact.catalog.") ? "catalog"
                        : type.getName().startsWith("com.afrotransact.taxreturns.") ? "tax-returns" : null;
                if (module == null) {
                    continue;
                }
                Object target = AopTestUtils.getUltimateTargetObject(bean);
                for (Field field : target.getClass().getDeclaredFields()) {
                    if (field.getType() == JdbcClient.class) {
                        field.setAccessible(true);
                        assertThat(field.get(target)).as(type.getSimpleName() + "." + field.getName())
                                .isSameAs(ctx.getBean(module + ".jdbcClient"));
                        checked++;
                    }
                }
            }
            assertThat(checked).as("module classes holding a JdbcClient").isGreaterThan(20);
        }
    }

    @Test
    void readinessListsBothModulesAndEachHasItsOwnGroup() {
        try (ConfigurableApplicationContext ctx = CoreRun.start()) {
            String readiness = CoreRun.get(ctx, "/actuator/health/readiness");
            assertThat(readiness).contains("\"status\":\"UP\"").contains("catalogModule").contains("taxreturnsModule");
            assertThat(readiness).as("readiness must not include the live database check").doesNotContain("Database");
            assertThat(CoreRun.get(ctx, "/actuator/health/catalog")).contains("catalogDatabase").contains("\"UP\"");
            assertThat(CoreRun.get(ctx, "/actuator/health/tax-returns")).contains("taxreturnsDatabase");
        }
    }

    @Test
    void aSwitchedOffModuleIsNotInReadiness() {
        try (ConfigurableApplicationContext ctx = CoreRun.start("at.modules.tax-returns.enabled=false",
                "at.catalog.mode=http")) {
            String readiness = CoreRun.get(ctx, "/actuator/health/readiness");
            assertThat(readiness).contains("catalogModule").doesNotContain("taxreturnsModule");
        }
    }

    @Test
    void catalogCallsTaxReturnsInProcessAsItsOwnPrincipal() {
        try (ConfigurableApplicationContext ctx = CoreRun.start()) {
            TaxResolver resolver = ctx.getBean(TaxResolver.class);
            TaxDecisionAuthority authority = ctx.getBean(TaxDecisionAuthority.class);
            assertThat(resolver).isInstanceOf(InProcessTaxResolver.class);
            assertThat(authority).isInstanceOf(InProcessTaxDecisionAuthority.class);
            // tax:classify is granted: the call reaches the rules, which say there is no such profile (HTTP 404 over the wire).
            assertThatThrownBy(() -> resolver.resolve(unknownCategory()))
                    .isInstanceOf(TaxResolver.TaxProfileNotFound.class);
            // the batched read answers; a variant with no stored decision is not RESOLVED.
            Map<String, TaxDecisionAuthority.Answer> answers = authority.ask(List.of(
                    new TaxDecisionAuthority.Ask("ATIN-NOT-THERE", "org_x")));
            assertThat(answers.values()).allSatisfy(a -> assertThat(a.resolved()).isFalse());
        }
    }

    @Test
    void aModuleWithoutTheScopeIsRefusedExactlyAsOverHttp() {
        try (ConfigurableApplicationContext ctx = CoreRun.start(
                "at.modules.catalog.caller-scopes[0]=offers:read")) {
            TaxResolver resolver = ctx.getBean(TaxResolver.class);
            assertThatThrownBy(() -> resolver.resolve(unknownCategory()))
                    .isInstanceOf(TaxResolver.TaxServiceUnavailable.class).hasMessageContaining("tax:classify");
            TaxDecisionAuthority authority = ctx.getBean(TaxDecisionAuthority.class);
            assertThatThrownBy(() -> authority.ask(List.of(new TaxDecisionAuthority.Ask("A1", "org_x"))))
                    .isInstanceOf(TaxDecisionAuthority.Unavailable.class);
        }
    }

    @Test
    void thePublishedApiFailsClosedWithoutACallerAndForTheWrongPrincipal() {
        try (ConfigurableApplicationContext ctx = CoreRun.start()) {
            TaxClassificationApi api = ctx.getBean(TaxClassificationApi.class);
            TaxClassificationApi.ResolveRequest request = new TaxClassificationApi.ResolveRequest("X", null, Map.of(),
                    Map.of());
            assertThatThrownBy(() -> api.resolve(request)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without a caller principal");
            // A caller with no scopes at all is refused by the use case's own permission check, not by this API.
            CallerPrincipal nobody = new CallerPrincipal("catalog", "catalog-service", Set.of());
            assertThatThrownBy(() -> InProcessCalls.as(nobody, () -> api.resolve(request)))
                    .hasMessageContaining("tax:classify");
        }
    }

    @Test
    void taxReturnsReadsCatalogInProcessToo() {
        try (ConfigurableApplicationContext ctx = CoreRun.start()) {
            ProductFacts facts = ctx.getBean(ProductFacts.class);
            assertThat(facts.getClass().getSimpleName()).isEqualTo("InProcessProductFacts");
            assertThat(facts.lookup(List.of("ATIN-NOT-THERE"))).isEmpty();
        }
    }

    @Test
    void httpModeKeepsTheHttpAdaptersByConfiguration() {
        try (ConfigurableApplicationContext ctx = CoreRun.start("at.catalog.mode=http", "at.taxreturns.mode=http")) {
            assertThat(ctx.getBean(TaxResolver.class).getClass().getSimpleName()).isEqualTo("HttpTaxResolver");
            assertThat(ctx.getBean(TaxDecisionAuthority.class).getClass().getSimpleName())
                    .isEqualTo("HttpTaxDecisionAuthority");
            assertThat(ctx.getBean(ProductFacts.class).getClass().getSimpleName()).isEqualTo("HttpProductFacts");
        }
    }

    @Test
    void inProcessModeWithTheTargetModuleOffFailsTheStartNamingBothModules() {
        // an assumption failing inside the lambda would be swallowed by assertThatThrownBy and fail the test instead of skipping it
        Assumptions.assumeTrue(CoreRun.databaseAvailable(), "CORE_IT_DB=host:port is not set: no PostgreSQL for the core context test");
        assertThatThrownBy(() -> CoreRun.start("at.modules.tax-returns.enabled=false"))
                .hasRootCauseMessage("Module 'catalog' is configured at.catalog.mode=in-process but module 'tax-returns' "
                        + "is not running in this JVM: enable it (at.modules.tax-returns.enabled=true) or set "
                        + "at.catalog.mode=http");
    }

    @Test
    void aDisabledModuleOpensNoPoolAndCatalogRunsOnHttpMode() {
        try (ConfigurableApplicationContext ctx = CoreRun.start("at.modules.tax-returns.enabled=false",
                "at.catalog.mode=http")) {
            assertThat(ctx.containsBean("tax-returns.dataSource")).isFalse();
            assertThat(ctx.containsBean("catalog.dataSource")).isTrue();
            assertThat(ctx.getBean(TaxResolver.class).getClass().getSimpleName()).isEqualTo("HttpTaxResolver");
        }
    }

    @Test
    void aFailedMigrationStopsTheStartAndNamesTheModule() {
        Assumptions.assumeTrue(CoreRun.databaseAvailable(), "CORE_IT_DB=host:port is not set: no PostgreSQL for the core context test");
        assertThatThrownBy(() -> CoreRun.start("at.modules.catalog.datasource.url=jdbc:postgresql://"
                + System.getenv("CORE_IT_DB") + "/no_such_database"))
                .hasStackTraceContaining("Module 'catalog' failed to migrate database 'no_such_database'");
    }

    @Test
    void everyMeterCarriesModuleAndServiceAndEachModuleHasItsSchedulerPool() {
        try (ConfigurableApplicationContext ctx = CoreRun.start()) {
            String scrape = CoreRun.get(ctx, "/actuator/prometheus");
            assertThat(scrape).contains("hikaricp_connections_max{env=\"local\",module=\"catalog\",pool=\"catalog\","
                    + "service=\"catalog\"} 8.0");
            assertThat(scrape).contains("hikaricp_connections_max{env=\"local\",module=\"tax-returns\","
                    + "pool=\"tax-returns\",service=\"tax-returns\"} 6.0");
            assertThat(scrape.lines().filter(l -> !l.startsWith("#") && !l.isBlank()))
                    .allSatisfy(l -> assertThat(l).contains("module=\"").contains("service=\""));
            Set<String> threads = Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
                    .collect(Collectors.toSet());
            assertThat(threads).anyMatch(n -> n.startsWith("sched-catalog-"))
                    .anyMatch(n -> n.startsWith("sched-tax-returns-"));
        }
    }
}
