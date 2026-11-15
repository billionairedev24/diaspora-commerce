package com.afrotransact.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts afrotransact-core for a test against a real PostgreSQL. Testcontainers needs Docker; where there is none (this
 * sandbox) point {@code CORE_IT_DB=host:port} at any PostgreSQL 17 that has the roles and databases {@code catalog} and
 * {@code taxreturns} (password = name) and the tests run; without it they are skipped, not passed.
 */
final class CoreRun {

    private CoreRun() {
    }

    /** GET {@code path} on the running application and return the body. */
    static String get(ConfigurableApplicationContext ctx, String path) {
        try {
            String port = ctx.getEnvironment().getProperty("local.server.port");
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static boolean databaseAvailable() {
        return System.getenv("CORE_IT_DB") != null && !System.getenv("CORE_IT_DB").isBlank();
    }

    static ConfigurableApplicationContext start(String... extra) {
        Assumptions.assumeTrue(databaseAvailable(), "CORE_IT_DB=host:port is not set: no PostgreSQL for the core context test");
        String db = System.getenv("CORE_IT_DB");
        List<String> properties = new ArrayList<>(List.of(
                "spring.profiles.active=local",
                "at.modules.catalog.datasource.url=jdbc:postgresql://" + db + "/catalog",
                "at.modules.catalog.datasource.username=catalog",
                "at.modules.catalog.datasource.password=catalog",
                "at.modules.tax-returns.datasource.url=jdbc:postgresql://" + db + "/taxreturns",
                "at.modules.tax-returns.datasource.username=taxreturns",
                "at.modules.tax-returns.datasource.password=taxreturns",
                // The local profile uses catalog's dataset fake for category facts; the tests exercise the real resolver.
                "at.catalog.tax.resolver=http",
                "at.consumers.enabled=false",
                "at.modules.catalog.events.relay-enabled=false",
                "at.modules.tax-returns.events.relay-enabled=false",
                "spring.kafka.bootstrap-servers=localhost:1",
                "server.port=0"));
        // A repeated command-line option is joined with a comma by Spring, not overridden: the last value for a key wins here.
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String p : properties) {
            byKey.put(p.substring(0, p.indexOf('=')), p);
        }
        for (String p : extra) {
            byKey.put(p.substring(0, p.indexOf('=')), p);
        }
        properties = new ArrayList<>(byKey.values());
        // Command-line arguments: the highest precedence, above the application's own application.yml (default properties are not).
        return new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.SERVLET)
                .run(properties.stream().map(p -> "--" + p).toArray(String[]::new));
    }
}
