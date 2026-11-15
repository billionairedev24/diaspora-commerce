package com.afrotransact.module.core;

/** A module could not start; the message always names the module so that the operator knows what to fix or disable. */
public class ModuleStartupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String module;

    public ModuleStartupException(String module, String message, Throwable cause) {
        super("Module '" + module + "' " + message, cause);
        this.module = module;
    }

    public String module() {
        return module;
    }
}
