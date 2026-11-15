package com.afrotransact.platform.web.module;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.afrotransact.module.ModuleMode;
import org.springframework.context.annotation.Conditional;

/**
 * Selects the adapter of one module-to-module edge: matches when the edge {@code caller -> callee} runs in {@code value} mode.
 * The edge's mode is {@code at.<caller>.<callee>.mode} (module names without dashes, for example {@code at.taxreturns.aigateway.mode}),
 * else {@code at.<caller>.mode}, else {@code http} (the standalone service). One edge, one adapter: the in-process adapter carries
 * {@code IN_PROCESS}, the HTTP adapter {@code HTTP}, so exactly one of them is a bean.
 *
 * <p>A mode that comes only from the caller-level default {@code at.<caller>.mode} gives way to {@code http} when the callee has
 * been switched off in this application ({@code at.modules.<callee>.enabled=false}): the default must not stop a start that left a
 * module out on purpose. An edge set explicitly ({@code at.<caller>.<callee>.mode=in-process}) is never overridden: with the callee
 * off, the adapter's start check fails naming both modules.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(OnModuleModeCondition.class)
public @interface ConditionalOnModuleMode {

    /** The consuming module, for example {@code catalog} or {@code tax-returns}. */
    String caller();

    /** The called module, for example {@code orders} or {@code ai-gateway}. */
    String callee();

    /** The mode this adapter serves. */
    ModuleMode value();
}
