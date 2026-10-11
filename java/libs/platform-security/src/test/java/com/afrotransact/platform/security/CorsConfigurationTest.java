package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class CorsConfigurationTest {

    private final PlatformSecurityAutoConfiguration config = new PlatformSecurityAutoConfiguration();

    @Test
    void allowsOnlyTheConfiguredOrigins() {
        CorsConfigurationSource source = config.corsConfigurationSource(
                new PlatformSecurityProperties(false, null, null, List.of("http://localhost:8081")));
        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/v1/services/search"));

        assertThat(cors).isNotNull();
        assertThat(cors.checkOrigin("http://localhost:8081")).isEqualTo("http://localhost:8081");
        assertThat(cors.checkOrigin("http://evil.example")).isNull();
    }

    @Test
    void noOriginsConfiguredRefusesEveryCrossOriginCall() {
        CorsConfigurationSource source = config.corsConfigurationSource(new PlatformSecurityProperties(null, null, null, null));
        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/v1/anything"));

        assertThat(cors).isNotNull();
        assertThat(cors.checkOrigin("http://localhost:8081")).isNull();
    }
}
