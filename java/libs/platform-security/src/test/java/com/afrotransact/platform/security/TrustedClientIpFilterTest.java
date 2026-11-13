package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TrustedClientIpFilterTest {

    private final TrustedClientIpFilter filter = new TrustedClientIpFilter(new ClientIpProperties("CF-Connecting-IP", null));

    private String remoteAddrSeenBy(MockHttpServletRequest request) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                seen.set(((HttpServletRequest) req).getRemoteAddr());
            }
        });
        return seen.get();
    }

    private static MockHttpServletRequest from(String peer, String header, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr(peer);
        if (header != null) {
            request.addHeader(header, value);
        }
        return request;
    }

    @Test
    void trustedProxyPeerGivesTheEdgeHeaderAsTheRemoteAddress() throws Exception {
        assertThat(remoteAddrSeenBy(from("10.42.0.7", "CF-Connecting-IP", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(remoteAddrSeenBy(from("10.42.0.7", "CF-Connecting-IP", "2001:db8::1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void aDirectPeerOutsideTheTrustedRangesCannotForgeTheHeader() throws Exception {
        assertThat(remoteAddrSeenBy(from("198.51.100.20", "CF-Connecting-IP", "203.0.113.9"))).isEqualTo("198.51.100.20");
    }

    @Test
    void xForwardedForIsNeverUsed() throws Exception {
        assertThat(remoteAddrSeenBy(from("10.42.0.7", "X-Forwarded-For", "6.6.6.6, 203.0.113.9"))).isEqualTo("10.42.0.7");
        MockHttpServletRequest both = from("10.42.0.7", "CF-Connecting-IP", "203.0.113.9");
        both.addHeader("X-Forwarded-For", "6.6.6.6");
        assertThat(remoteAddrSeenBy(both)).isEqualTo("203.0.113.9");
    }

    @Test
    void malformedValuesAndHostnamesFallBackToThePeer() throws Exception {
        assertThat(remoteAddrSeenBy(from("10.42.0.7", "CF-Connecting-IP", "evil.example.com"))).isEqualTo("10.42.0.7");
        assertThat(remoteAddrSeenBy(from("10.42.0.7", "CF-Connecting-IP", "1.2.3.4, 5.6.7.8"))).isEqualTo("10.42.0.7");
        assertThat(remoteAddrSeenBy(from("10.42.0.7", "CF-Connecting-IP", ""))).isEqualTo("10.42.0.7");
    }

    @Test
    void explicitTrustedProxiesReplaceThePrivateRanges() throws Exception {
        TrustedClientIpFilter narrow = new TrustedClientIpFilter(new ClientIpProperties("CF-Connecting-IP", List.of("10.0.5.0/24")));
        AtomicReference<String> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                seen.set(((HttpServletRequest) req).getRemoteAddr());
            }
        };
        narrow.doFilter(from("10.9.9.9", "CF-Connecting-IP", "203.0.113.9"), new MockHttpServletResponse(), chain);
        assertThat(seen.get()).isEqualTo("10.9.9.9");
    }
}
