package com.afrotransact.platform.security;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.web.error.ModuleFailures;

/**
 * The entry guard every published module API runs its calls through. It refuses to run without an in-process caller, and
 * when the actor in force is not that caller (so a misconfigured application fails closed instead of running as whoever is on
 * the thread), then runs the use case, whose own permission checks decide as they do for a token. Errors leave as
 * {@link ModuleApiException} with the status the HTTP endpoint answers.
 */
public final class ModuleEntry {

    private final ActorResolver actors;

    public ModuleEntry(ActorResolver actors) {
        this.actors = actors;
    }

    /** Runs {@code body} as the in-process caller; platform errors become {@link ModuleApiException}. */
    public <T> T run(Supplier<T> body) {
        return run(body, ex -> Optional.empty());
    }

    /**
     * @param own the module's own mapping of its error types (the one its advice applies); empty falls back to the platform's
     */
    public <T> T run(Supplier<T> body, Function<RuntimeException, Optional<ModuleApiException>> own) {
        CallerPrincipal caller = InProcessCalls.require();
        String subject = actors.current().subject();
        if (!caller.clientId().equals(subject)) {
            throw new IllegalStateException("The actor in force (" + subject + ") is not the in-process caller ("
                    + caller.clientId() + "): the ActorResolver does not honour in-process callers");
        }
        try {
            return body.get();
        } catch (ModuleApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw own.apply(ex).or(() -> ModuleFailures.ofPlatform(ex)).<RuntimeException>map(m -> m).orElse(ex);
        }
    }

    /** Same for a call with no result. */
    public void runVoid(Runnable body, Function<RuntimeException, Optional<ModuleApiException>> own) {
        run(() -> {
            body.run();
            return null;
        }, own);
    }
}
