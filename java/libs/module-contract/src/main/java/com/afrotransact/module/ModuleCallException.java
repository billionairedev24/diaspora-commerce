package com.afrotransact.module;

/** An in-process module call could not be made (the target module is not running in this JVM). */
public class ModuleCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModuleCallException(String message) {
        super(message);
    }
}
