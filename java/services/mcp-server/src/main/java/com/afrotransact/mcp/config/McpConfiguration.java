package com.afrotransact.mcp.config;

import java.time.Clock;

import com.afrotransact.mcp.adapter.in.mcp.GuardedMcpTransport;
import com.afrotransact.mcp.adapter.in.mcp.McpCallerExtractor;
import com.afrotransact.mcp.application.ToolGuardService;
import com.afrotransact.mcp.domain.DefaultPiiMasker;
import com.afrotransact.mcp.domain.DefaultShipmentStatusPolicy;
import com.afrotransact.mcp.domain.DefaultToolAccessPolicy;
import com.afrotransact.mcp.domain.PiiMasker;
import com.afrotransact.mcp.domain.RateLimiter;
import com.afrotransact.mcp.domain.ShipmentStatusPolicy;
import com.afrotransact.mcp.domain.TokenBucketRateLimiter;
import com.afrotransact.mcp.domain.ToolAccessPolicy;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PlatformSecurityProperties;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the Spring AI MCP server (Streamable HTTP, stateless mode) with the platform guard: our transport bean carries
 * the authenticated caller into the MCP context, and the primary {@link GuardedMcpTransport} filters
 * {@code tools/list}, admits {@code tools/call} (scopes, permissions, rate limit) and audits every call.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({McpProperties.class, McpServerStreamableHttpProperties.class})
class McpConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ToolAccessPolicy toolAccessPolicy() {
        return new DefaultToolAccessPolicy();
    }

    @Bean
    RateLimiter rateLimiter(McpProperties props, Clock clock) {
        return new TokenBucketRateLimiter(props.rateLimit().capacity(), props.rateLimit().refillPerMinute(), clock);
    }

    @Bean
    ShipmentStatusPolicy shipmentStatusPolicy() {
        return new DefaultShipmentStatusPolicy();
    }

    @Bean
    PiiMasker piiMasker() {
        return new DefaultPiiMasker();
    }

    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper mcpJson, McpServerStreamableHttpProperties http,
            ActorResolver actors, PlatformSecurityProperties security, McpProperties props) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mcpJson))
                .messageEndpoint(http.getMcpEndpoint())
                .contextExtractor(new McpCallerExtractor(actors, security.enabled()))
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(props.allowedOrigins()).build())
                .build();
    }

    @Bean
    @Primary
    McpStatelessServerTransport guardedMcpTransport(WebMvcStatelessServerTransport transport, ToolGuardService guard,
                                                    JsonMapper json) {
        return new GuardedMcpTransport(transport, guard, json);
    }
}
