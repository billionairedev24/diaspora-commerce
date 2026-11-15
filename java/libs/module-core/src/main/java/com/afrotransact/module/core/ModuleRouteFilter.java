package com.afrotransact.module.core;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records which module a request was addressed to: the one that owns the listener port it arrived on
 * ({@code at.core.module-ports.<port>=<module>}, the module's old standalone port) or the one named in a
 * {@code /modules/<name>/...} path prefix (stripped here, so security matchers and handlers see the module's own path). Only used to
 * choose between controllers that map the same route; see {@link ModuleRouteCondition}.
 */
final class ModuleRouteFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/modules/";

    private final ModuleRegistry modules;
    private final Map<Integer, String> modulePorts;

    ModuleRouteFilter(ModuleRegistry modules, Map<Integer, String> modulePorts) {
        this.modules = modules;
        this.modulePorts = modulePorts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(PREFIX)) {
            int slash = path.indexOf('/', PREFIX.length());
            String name = slash < 0 ? path.substring(PREFIX.length()) : path.substring(PREFIX.length(), slash);
            if (modules.mounted(name)) {
                request.setAttribute(ModuleRouteCondition.TARGET, name);
                String stripped = slash < 0 ? "/" : path.substring(slash);
                chain.doFilter(new Stripped(request, stripped), response);
                return;
            }
        }
        String byPort = modulePorts.get(request.getLocalPort());
        if (byPort != null) {
            request.setAttribute(ModuleRouteCondition.TARGET, byPort);
        }
        chain.doFilter(request, response);
    }

    private static final class Stripped extends HttpServletRequestWrapper {

        private final String path;

        Stripped(HttpServletRequest request, String path) {
            super(request);
            this.path = path;
        }

        @Override
        public String getRequestURI() {
            return getContextPath() + path;
        }

        @Override
        public String getServletPath() {
            return path;
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
