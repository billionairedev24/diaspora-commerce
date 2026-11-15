package com.afrotransact.module.core;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares one module of the application: where its code lives and how its meters and listeners are recognised. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
@Documented
public @interface ModuleSpec {

    /** Module name, the key of {@code at.modules.<name>}, for example {@code tax-returns}. */
    String name();

    /** The service's short name, the value of the {@code service} label the dashboards and alerts already use. */
    String service();

    /** Root package of the module's code, for example {@code com.afrotransact.taxreturns}. */
    String basePackage();

    /** The module's standalone {@code @SpringBootApplication} class; it is not mounted. */
    String application();

    /** Meter-name and Kafka-client-id prefixes that belong to the module ({@code tax.}, {@code taxreturns-}). */
    String[] meterPrefixes() default {};
}
