package com.afrotransact.module.core;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Adds {@code module} and {@code service} to HTTP server metrics and spans, by the package of the controller that answered. */
final class ModuleServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    private final ModuleRegistry modules;

    ModuleServerRequestObservationConvention(ModuleRegistry modules) {
        this.modules = modules;
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        String module = ModuleRegistry.CORE;
        Object handler = context.getCarrier().getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod method) {
            module = modules.moduleOf(method.getBeanType()).map(ModuleDefinition::name).orElse(ModuleRegistry.CORE);
        }
        return super.getLowCardinalityKeyValues(context).and(KeyValue.of("module", module),
                KeyValue.of("service", modules.serviceOf(module)));
    }
}
