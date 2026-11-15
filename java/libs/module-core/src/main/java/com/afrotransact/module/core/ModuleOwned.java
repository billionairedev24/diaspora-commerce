package com.afrotransact.module.core;

/** Implemented by infrastructure beans that are created once per module but live outside the module's packages. */
public interface ModuleOwned {

    String module();
}
