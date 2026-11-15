package com.afrotransact.module.core;

/** Bean-definition attributes that tell the resolver whose a bean is. */
final class ModuleAttributes {

    /** The module a bean belongs to. A module-owned bean is injectable only into that module's own classes. */
    static final String MODULE = "at.module";
    /** Marks the routing transaction manager: it serves infrastructure (framework interceptors), never a module directly. */
    static final String ROUTER = "@router";

    private ModuleAttributes() {
    }
}
