package com.afrotransact.core;

import java.util.List;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional extra listeners ({@code at.core.legacy-ports}) that serve the very same application as the main port. Every module
 * is still reachable on its old standalone port (8101 for catalog ...), so a module left in {@code http} mode, a tool, a script
 * or gateway-bff configured as before keeps working while the modules run in one JVM. Off by default; the local profile turns
 * it on. In production the one port and in-process calls are used.
 */
@Configuration(proxyBeanMethods = false)
class LegacyPortsConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> legacyPortsCustomizer(
            @Value("${at.core.legacy-ports:}") List<Integer> ports) {
        return factory -> ports.stream().filter(p -> p != null && p > 0).forEach(port -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            factory.addAdditionalConnectors(connector);
        });
    }
}
