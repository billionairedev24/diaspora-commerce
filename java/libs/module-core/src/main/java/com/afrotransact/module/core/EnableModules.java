package com.afrotransact.module.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * Mounts the listed modules in this application: scans their packages and gives each its own infrastructure. The mounting itself
 * is done by {@link ModuleMounts}, installed by {@link ModuleMountsInitializer} (spring.factories) so that it runs first.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import(ModuleCoreConfiguration.class)
public @interface EnableModules {

    ModuleSpec[] value();
}
