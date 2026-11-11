package com.afrotransact.taxreturns.it;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link CategoryTaxEngineScenarios} on a local PostgreSQL without Docker:
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://host:port/db} (optionally {@code AT_IT_POSTGRES_USER} and
 * {@code AT_IT_POSTGRES_PASSWORD}); skipped when it is not set.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.taxreturns.tax.stripe.secret-key=sk_test_integration"})
@ActiveProfiles("local")
@Import(StubUpstreams.class)
@EnabledIfEnvironmentVariable(named = "AT_IT_POSTGRES_URL", matches = ".+")
class CategoryTaxEngineLocalIT extends CategoryTaxEngineScenarios {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AT_IT_POSTGRES_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", ""));
    }

    @Autowired
    CategoryTaxEngineLocalIT(ApplicationContext context) {
        super(context);
    }
}
