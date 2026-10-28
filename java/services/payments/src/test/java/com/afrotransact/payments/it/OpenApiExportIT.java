package com.afrotransact.payments.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Writes the served OpenAPI document to $AT_EXPORT_OPENAPI (tools/contracts/export_java_openapi.sh without Docker). */
@EnabledIfEnvironmentVariable(named = "AT_EXPORT_OPENAPI", matches = ".+")
class OpenApiExportIT extends PaymentsIntegrationTest {

    @Test
    void export() throws IOException {
        Files.writeString(Path.of(System.getenv("AT_EXPORT_OPENAPI")), send("GET", "/v3/api-docs.yaml", null, null)
                .body());
    }
}
