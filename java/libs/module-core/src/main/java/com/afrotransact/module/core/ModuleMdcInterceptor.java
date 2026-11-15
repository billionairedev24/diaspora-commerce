package com.afrotransact.module.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Puts the module that answers a request into the logging context, so every log line of the request carries {@code module}. */
final class ModuleMdcInterceptor implements HandlerInterceptor {

    static final String KEY = "module";

    private final ModuleRegistry modules;

    ModuleMdcInterceptor(ModuleRegistry modules) {
        this.modules = modules;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            modules.moduleOf(method.getBeanType()).ifPresent(module -> MDC.put(KEY, module.name()));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        MDC.remove(KEY);
    }
}
