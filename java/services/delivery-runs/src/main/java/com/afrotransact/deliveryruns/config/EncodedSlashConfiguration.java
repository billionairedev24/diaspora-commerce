package com.afrotransact.deliveryruns.config;

import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * Area keys contain slashes: the staff BFF deletes {@code /v1/preorder-areas/US%2FTX%2FLubbock}. Tomcat decodes the
 * encoded slash in the servlet path (instead of rejecting it), the raw request URI keeps it and the Spring Security
 * firewall allows it there; the handler receives the decoded key. Every other firewall rule stays on.
 */
@Configuration(proxyBeanMethods = false)
class EncodedSlashConfiguration {

    @Bean
    TomcatConnectorCustomizer encodedSlashDecode() {
        return connector -> connector.setEncodedSolidusHandling("decode");
    }

    @Bean
    WebSecurityCustomizer encodedSlashFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        return web -> web.httpFirewall(firewall);
    }
}
