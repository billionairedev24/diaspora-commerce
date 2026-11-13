package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class StaffOrServiceOnlyInterceptorTest {

    private final AtomicReference<Actor> current = new AtomicReference<>();
    private final StaffOrServiceOnlyInterceptor interceptor = new StaffOrServiceOnlyInterceptor(current::get);

    @StaffOrServiceOnly
    static class StaffController {
        void any() {
        }
    }

    static class MixedController {
        @StaffOrServiceOnly
        void staffRoute() {
        }

        void openRoute() {
        }
    }

    private static Actor actor(PrincipalType type, String... permissions) {
        return new Actor("sub", type, Optional.empty(), Set.of(), Set.of(permissions));
    }

    private boolean call(Object bean, String method) throws Exception {
        Method m = bean.getClass().getDeclaredMethod(method);
        return interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(bean, m));
    }

    @Test
    void staffAndServicesPassOnMarkedControllersAndMethods() throws Exception {
        for (PrincipalType type : new PrincipalType[] {PrincipalType.STAFF, PrincipalType.SERVICE}) {
            current.set(actor(type, "x:y"));
            assertThat(call(new StaffController(), "any")).isTrue();
            assertThat(call(new MixedController(), "staffRoute")).isTrue();
        }
        current.set(actor(PrincipalType.CUSTOMER, "*"));
        assertThat(call(new StaffController(), "any")).as("super admin wildcard").isTrue();
    }

    @Test
    void everyOtherPrincipalTypeIsRefusedEvenWithThePermission() {
        for (PrincipalType type : PrincipalType.values()) {
            if (type == PrincipalType.STAFF || type == PrincipalType.SERVICE) {
                continue;
            }
            current.set(actor(type, "retail:read", "delivery-runs:read"));
            assertThatThrownBy(() -> call(new StaffController(), "any")).as(type.name())
                    .isInstanceOf(ForbiddenActionException.class).hasMessageContaining("staff");
            assertThatThrownBy(() -> call(new MixedController(), "staffRoute")).as(type.name())
                    .isInstanceOf(ForbiddenActionException.class);
        }
    }

    @Test
    void unmarkedHandlersAreNotChecked() throws Exception {
        current.set(actor(PrincipalType.CUSTOMER));
        assertThatCode(() -> call(new MixedController(), "openRoute")).doesNotThrowAnyException();
        assertThat(call(new MixedController(), "openRoute")).isTrue();
    }
}
