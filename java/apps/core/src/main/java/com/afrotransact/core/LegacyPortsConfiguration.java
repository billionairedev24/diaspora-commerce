package com.afrotransact.core;

import java.util.Map;
import java.util.Set;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Optional extra listeners ({@code at.core.module-ports.<port>=<module>}) that serve the very same application as the main port. Every module
 * is still reachable on its old standalone port (8101 for catalog ...), so a module left in {@code http} mode, a tool, a script
 * or gateway-bff configured as before keeps working while the modules run in one JVM. Off by default; the local profile turns
 * it on. In production the one port and in-process calls are used.
 *
 * <p>Switch: {@code at.core.legacy-ports.enabled}, default {@code false}; only the {@code local} profile sets it to {@code true} (for clients outside the JVM:
 * mobile apps, MCP links, the Stripe webhook forward, the seeder). With it {@code false} no extra listener is opened even when
 * {@code module-ports} is set. The ports are compatibility only: no module calls another over them (mcp-server's tool edges were the
 * last Java-to-Java HTTP calls and are in-process), and {@code SingleEntryPortTest} holds that dev, uat and prod have the one port.
 */
@Configuration(proxyBeanMethods = false)
class LegacyPortsConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> legacyPortsCustomizer(
            Environment environment) {
        Set<Integer> ports = Binder.get(environment).bind("at.core.module-ports",
                Bindable.mapOf(Integer.class, String.class)).orElse(Map.of()).keySet();
        if (!environment.getProperty("at.core.legacy-ports.enabled", Boolean.class, false)) {
            return factory -> { };
        }
        return factory -> ports.stream().filter(p -> p != null && p > 0).forEach(port -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            factory.addAdditionalConnectors(connector);
        });
    }
}
