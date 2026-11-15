package com.afrotransact.module;

/** The registry of what each module may do when it calls another module in-process (configuration, not self-asserted). */
public interface CallerPrincipals {

    /**
     * @return the principal of {@code module}
     * @throws IllegalStateException when the module has no entry: an unregistered module gets no authority, never a default
     */
    CallerPrincipal principalFor(String module);
}
