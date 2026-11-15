package com.afrotransact.module.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/** Mounts the listed modules in this application: scans their packages and gives each its own infrastructure. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import({ModuleMounts.class, ModuleCoreConfiguration.class})
public @interface EnableModules {

    ModuleSpec[] value();
}
