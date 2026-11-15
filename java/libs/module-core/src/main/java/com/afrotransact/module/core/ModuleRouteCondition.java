package com.afrotransact.module.core;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.mvc.condition.RequestCondition;

/**
 * Tells two controllers that map the same method and path apart when they belong to different modules (three such routes exist:
 * {@code POST /v1/returns}, {@code POST /v1/receipts}, {@code POST /v1/reconciliation/runs}). A request that came through a
 * module's own port or its {@code /modules/<name>/} prefix prefers that module's handler; any other request prefers the module
 * mounted first. The condition never excludes a handler by itself, so no route becomes unreachable.
 */
final class ModuleRouteCondition implements RequestCondition<ModuleRouteCondition> {

    /** Request attribute holding the module the request was addressed to, set by {@link ModuleRouteFilter}. */
    static final String TARGET = ModuleRouteCondition.class.getName() + ".target";

    private final String module;
    private final int order;

    ModuleRouteCondition(String module, int order) {
        this.module = module;
        this.order = order;
    }

    @Override
    public ModuleRouteCondition combine(ModuleRouteCondition other) {
        return other;
    }

    @Override
    public ModuleRouteCondition getMatchingCondition(HttpServletRequest request) {
        return this;
    }

    @Override
    public int compareTo(ModuleRouteCondition other, HttpServletRequest request) {
        Object target = request.getAttribute(TARGET);
        if (target != null) {
            boolean mine = module.equals(target);
            boolean theirs = other.module.equals(target);
            if (mine != theirs) {
                return mine ? -1 : 1;
            }
        }
        return Integer.compare(order, other.order);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModuleRouteCondition c && c.module.equals(module);
    }

    @Override
    public int hashCode() {
        return module.hashCode();
    }

    @Override
    public String toString() {
        return "[module=" + module + "]";
    }
}
