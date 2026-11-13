package com.afrotransact.platform.security;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Refuses a non-staff, non-service principal on handlers marked {@link StaffOrServiceOnly} (403 auth.staff-only). */
public class StaffOrServiceOnlyInterceptor implements HandlerInterceptor {

    private final ActorResolver actors;

    public StaffOrServiceOnlyInterceptor(ActorResolver actors) {
        this.actors = actors;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && isMarked(method)) {
            require(actors.current());
        }
        return true;
    }

    /** True when the actor is STAFF, SERVICE or holds the super-admin wildcard. */
    public static boolean allowed(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE
                || actor.permissions().contains("*");
    }

    static void require(Actor actor) {
        if (!allowed(actor)) {
            throw new ForbiddenActionException("auth.staff-only", "Only AfroTransact staff or services can do this");
        }
    }

    /** True when the handler method or its controller class carries {@link StaffOrServiceOnly}. */
    public static boolean isMarked(HandlerMethod method) {
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), StaffOrServiceOnly.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), StaffOrServiceOnly.class);
    }
}
