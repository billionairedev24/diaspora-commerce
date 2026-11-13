package com.afrotransact.platform.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes {@code request.getRemoteAddr()} the visitor address, for per-IP sign-in throttles, device and reset records.
 * The configured header is believed only when the direct peer is a trusted proxy and the value is a literal IP address;
 * otherwise (direct caller, malformed value) the socket address stays. Never reads X-Forwarded-For.
 */
final class TrustedClientIpFilter extends OncePerRequestFilter {

    private final String header;
    private final List<IpAddressMatcher> trusted;

    TrustedClientIpFilter(ClientIpProperties props) {
        this.header = props.header();
        this.trusted = props.trustedProxies().stream().map(IpAddressMatcher::new).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = request.getHeader(header);
        if (value != null && trusted.stream().anyMatch(m -> m.matches(request.getRemoteAddr()))) {
            String ip = literalIp(value.trim());
            if (ip != null) {
                chain.doFilter(new RemoteAddrRequest(request, ip), response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The normalised address if the text is a literal IPv4/IPv6 address (no DNS lookup), else null. */
    private static String literalIp(String text) {
        if (text.isEmpty() || text.length() > 45 || !text.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(text).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static final class RemoteAddrRequest extends HttpServletRequestWrapper {
        private final String remoteAddr;

        RemoteAddrRequest(HttpServletRequest request, String remoteAddr) {
            super(request);
            this.remoteAddr = remoteAddr;
        }

        @Override
        public String getRemoteAddr() {
            return remoteAddr;
        }

        @Override
        public String getRemoteHost() {
            return remoteAddr;
        }
    }
}
