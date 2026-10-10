package com.afrotransact.offers.it;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base of the Testcontainers ITs: one PostgreSQL (PostGIS image) for the JVM, no Kafka broker (relay and listeners
 * off; listeners are invoked directly), stub catalog and switchable actors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1", "at.consumers.enabled=false",
    "at.offers.retail-org-id=" + OffersIntegrationTest.RETAIL_ORG})
@ActiveProfiles("local")
@Import({TestActors.class, StubCatalog.class, StubPickupLocations.class})
public abstract class OffersIntegrationTest {

    public static final String RETAIL_ORG = "org_afrotransactretail01";
    public static final String LAGOS = "org_lagostextilehub00001";
    public static final String MAMATEE = "org_mamateespantry000001";

    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        POSTGRES.start();
    }

    protected final JdbcClient jdbc;

    protected OffersIntegrationTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    /** A fresh random ATIN so tests sharing the database never collide. */
    protected static String atin() {
        return "AT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    protected List<String> outboxTypes(String aggregateId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, type")
                .param("id", aggregateId).query(String.class).list();
    }
}
