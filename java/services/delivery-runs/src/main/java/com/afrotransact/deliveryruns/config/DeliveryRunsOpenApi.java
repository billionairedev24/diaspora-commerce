package com.afrotransact.deliveryruns.config;

import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documents the RFC 9457 problem every operation can answer with (4XX, application/problem+json). */
@Configuration(proxyBeanMethods = false)
class DeliveryRunsOpenApi {

    private static final String PROBLEM = "Problem";

    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            Schema<?> problem = new ObjectSchema()
                    .description("RFC 9457 problem details; `code` is a stable delivery-runs.* (or auth.*) code")
                    .addProperty("type", new StringSchema().format("uri"))
                    .addProperty("title", new StringSchema())
                    .addProperty("status", new IntegerSchema())
                    .addProperty("detail", new StringSchema())
                    .addProperty("instance", new StringSchema())
                    .addProperty("code", new StringSchema().example("delivery-runs.invalid-transition"));
            openApi.getComponents().addSchemas(PROBLEM, problem);
            ApiResponse response = new ApiResponse().description("Problem (RFC 9457)").content(new Content()
                    .addMediaType("application/problem+json", new MediaType()
                            .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(op -> {
                boolean has4xx = op.getResponses() != null && op.getResponses().keySet().stream()
                        .anyMatch(code -> code.startsWith("4"));
                if (!has4xx && op.getResponses() != null) {
                    op.getResponses().putAll(Map.of("4XX", response));
                }
            }));
        };
    }
}
