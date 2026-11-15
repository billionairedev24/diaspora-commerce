package com.afrotransact.module.core;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Prints a failed module start as a short, actionable message instead of a stack trace. */
class ModuleStartupFailureAnalyzer extends AbstractFailureAnalyzer<ModuleStartupException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ModuleStartupException cause) {
        Throwable root = cause.getCause() == null ? cause : cause.getCause();
        String detail = root.getClass().getSimpleName() + ": " + root.getMessage();
        return new FailureAnalysis(cause.getMessage() + "\n" + detail,
                "Fix the module's database or migration, or start without it: at.modules." + cause.module()
                        + ".enabled=false (a module that other modules call in-process must then be switched to mode=http).",
                cause);
    }
}
