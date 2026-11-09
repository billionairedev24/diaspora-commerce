package com.afrotransact.taxreturns.it;

import com.afrotransact.platform.test.PostgresTestContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@link CategoryTaxEngineScenarios} on a Testcontainers PostgreSQL (needs Docker; runs in CI). */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.taxreturns.tax.stripe.secret-key=sk_test_integration"})
@ActiveProfiles("local")
@Testcontainers
@Import(StubUpstreams.class)
class CategoryTaxEngineIT extends CategoryTaxEngineScenarios {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    @Autowired
    CategoryTaxEngineIT(ApplicationContext context) {
        super(context);
    }
}
