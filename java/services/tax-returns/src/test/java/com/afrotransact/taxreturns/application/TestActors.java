package com.afrotransact.taxreturns.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;

/** A real {@link Access} over a staff actor holding the given permissions (use "*" for all). */
final class TestActors {

    private TestActors() {
    }

    static Access staff(String... permissions) {
        Actor actor = new Actor("staff_1", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of(permissions));
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.current()).thenReturn(actor);
        PermissionChecker checker = mock(PermissionChecker.class);
        when(checker.require(anyString())).thenAnswer(i -> {
            String p = i.getArgument(0);
            if (!actor.can(p)) {
                throw new ForbiddenActionException("test.forbidden", "Needs " + p);
            }
            return actor;
        });
        return new Access(actors, checker);
    }
}
