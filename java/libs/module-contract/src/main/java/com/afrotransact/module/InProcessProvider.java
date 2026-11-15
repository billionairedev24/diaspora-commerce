package com.afrotransact.module;

/** The provider side of an in-process module API: the guard every API adapter runs first. */
public final class InProcessProvider {

    private InProcessProvider() {
    }

    /**
     * Requires a caller and that the actor in force (its subject) IS that caller, so a misconfigured application fails closed
     * instead of running as whoever is on the thread.
     *
     * @param actorSubject the subject of the actor the platform resolved for the current call
     */
    public static CallerPrincipal requireActing(String actorSubject) {
        CallerPrincipal caller = InProcessCalls.require();
        if (!caller.clientId().equals(actorSubject)) {
            throw new IllegalStateException("The actor in force (" + actorSubject + ") is not the in-process caller ("
                    + caller.clientId() + "): the ActorResolver does not honour in-process callers");
        }
        return caller;
    }
}
