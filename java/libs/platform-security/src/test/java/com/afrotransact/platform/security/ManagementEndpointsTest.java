package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

class ManagementEndpointsTest {

    private static MockHttpServletRequest request(String path, int localPort) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setLocalPort(localPort);
        return request;
    }

    @Test
    void prometheusIsNotInTheAlwaysPublicPaths() {
        assertThat(Arrays.asList(PlatformSecurityAutoConfiguration.ALWAYS_PUBLIC))
                .contains("/actuator/health/**", "/actuator/info")
                .doesNotContain("/actuator/prometheus");
        assertThat(Arrays.asList(ManagementEndpoints.ALWAYS_PUBLIC_OPERATIONAL)).doesNotContain("/actuator/prometheus");
    }

    @Test
    void prometheusIsPublicOnlyOnTheDedicatedManagementPort() {
        MockEnvironment env = new MockEnvironment().withProperty("server.port", "8080")
                .withProperty("management.server.port", "9090");
        RequestMatcher matcher = ManagementEndpoints.prometheusOnManagementPort(env);

        assertThat(matcher.matches(request("/actuator/prometheus", 9090))).isTrue();
        assertThat(matcher.matches(request("/actuator/prometheus", 8080))).isFalse();
        assertThat(matcher.matches(request("/identity/actuator/prometheus", 9090))).isFalse();
        assertThat(matcher.matches(request("/v1/orders", 9090))).isFalse();
    }

    @Test
    void withoutASeparateManagementPortPrometheusStaysBehindAuthentication() {
        assertThat(ManagementEndpoints.prometheusOnManagementPort(new MockEnvironment())
                .matches(request("/actuator/prometheus", 8080))).isFalse();
        MockEnvironment same = new MockEnvironment().withProperty("server.port", "8080")
                .withProperty("management.server.port", "8080");
        assertThat(ManagementEndpoints.prometheusOnManagementPort(same).matches(request("/actuator/prometheus", 8080))).isFalse();
    }
}
