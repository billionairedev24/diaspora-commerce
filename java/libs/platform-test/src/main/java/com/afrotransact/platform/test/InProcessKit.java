package com.afrotransact.platform.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.DefaultPermissionChecker;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import org.mockito.internal.stubbing.defaultanswers.ReturnsEmptyValues;

/**
 * Test kit for a module's published API (S6, authorization parity): the actor inside an in-process call is the caller, a
 * use-case stand-in refuses a caller without the scope the way the real application service does, and the refusal must leave
 * the API as status 403.
 */
public final class InProcessKit {

    /** The actor in force inside an in-process call: the caller as a SERVICE with its scopes. */
    public static final ActorResolver ACTORS = () -> {
        CallerPrincipal caller = InProcessCalls.require();
        return new Actor(caller.clientId(), PrincipalType.SERVICE, Optional.empty(), Set.of(), caller.scopes());
    };

    private static final PermissionChecker CHECKER = new DefaultPermissionChecker(ACTORS);

    private InProcessKit() {
    }

    public static CallerPrincipal caller(String module, String... scopes) {
        return new CallerPrincipal(module, module + "-service", Set.of(scopes));
    }

    /** A caller with no scope at all. */
    public static CallerPrincipal unscoped(String module) {
        return caller(module);
    }

    /**
     * A stand-in for an application service: every method first requires {@code permission} through the real
     * {@link DefaultPermissionChecker}, then answers with a default value.
     */
    public static <T> T guarded(Class<T> service, String permission) {
        return mock(service, invocation -> {
            CHECKER.require(permission);
            return new ReturnsEmptyValues().answer(invocation);
        });
    }

    /** Asserts the call fails with status 403 when entered as {@code caller}. */
    public static void assertForbidden(CallerPrincipal caller, Runnable call) {
        assertThatThrownBy(() -> InProcessCalls.as(caller, () -> {
            call.run();
            return null;
        })).isInstanceOfSatisfying(ModuleApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(403);
            assertThat(ex.code()).isEqualTo("auth.missing-permission");
        });
    }
}
