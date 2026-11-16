package com.afrotransact.platform.security;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.EndUserActor;
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
        return guarded(body, own);
    }

    /**
     * The entry of an API method that acts for a signed-in person: the use case runs with {@code user} as the actor in force
     * (their subject, type, organisation, roles and permissions exactly as in their token), so its permission, organisation and
     * ownership checks decide as they do for the HTTP request. The calling module must still be an in-process caller and must hold
     * {@code callerScope}, the delegation scope that lets that module act for users on this API: a module that was never given it
     * is refused with 403 even though the person is genuine. The person is never turned into a service, and the caller's own
     * scopes never become the person's.
     *
     * @param callerScope the scope the calling module needs to act for users on this API, for example {@code identity:addresses.read}
     */
    public <T> T runAsUser(EndUserActor user, String callerScope, Supplier<T> body,
                           Function<RuntimeException, Optional<ModuleApiException>> own) {
        CallerPrincipal caller = InProcessCalls.require();
        if (user == null) {
            throw new ModuleApiException(401, "auth.unauthenticated", "No authenticated user");
        }
        if (!caller.scopes().contains(callerScope)) {
            throw new ModuleApiException(403, "auth.missing-permission", "Missing permission " + callerScope);
        }
        return InProcessCalls.withUser(user, () -> {
            Actor actor = actors.current();
            if (actor.type() == PrincipalType.SERVICE || !user.userId().equals(actor.subject())) {
                throw new IllegalStateException("The actor in force (" + actor.subject() + ", " + actor.type()
                        + ") is not the end user " + user.userId() + ": the ActorResolver does not honour end-user actors");
            }
            return guarded(body, own);
        });
    }

    /** {@link #runAsUser} with the platform's error mapping only. */
    public <T> T runAsUser(EndUserActor user, String callerScope, Supplier<T> body) {
        return runAsUser(user, callerScope, body, ex -> Optional.empty());
    }

    private <T> T guarded(Supplier<T> body, Function<RuntimeException, Optional<ModuleApiException>> own) {
        try {
            return body.get();
        } catch (ModuleApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw own.apply(ex).or(() -> ModuleFailures.ofPlatform(ex)).<RuntimeException>map(m -> m).orElse(ex);
        }
    }

    /** Same for a call with no result, platform errors only. */
    public void runVoid(Runnable body) {
        runVoid(body, ex -> Optional.empty());
    }

    /** Same for a call with no result. */
    public void runVoid(Runnable body, Function<RuntimeException, Optional<ModuleApiException>> own) {
        run(() -> {
            body.run();
            return null;
        }, own);
    }
}
