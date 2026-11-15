package com.afrotransact.module.core;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;

/** The configured authority of each mounted module: {@code at.modules.<name>.caller-scopes} and {@code .client-id}. */
final class ModuleCallerRegistry implements CallerPrincipals {

    private final ModuleRegistry modules;

    ModuleCallerRegistry(ModuleRegistry modules) {
        this.modules = modules;
    }

    @Override
    public CallerPrincipal principalFor(String module) {
        ModuleProperties settings = modules.properties(module);
        String clientId = settings.clientId() == null || settings.clientId().isBlank()
                ? modules.serviceOf(module) + "-service" : settings.clientId();
        return new CallerPrincipal(module, clientId, settings.callerScopes());
    }
}
