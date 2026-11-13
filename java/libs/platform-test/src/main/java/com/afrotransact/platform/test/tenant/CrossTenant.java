package com.afrotransact.platform.test.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.function.Function;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Assertions for cross-tenant tests: a foreign id is refused (403) or hidden (404), and lists never leak. */
public final class CrossTenant {

    private CrossTenant() {
    }

    /** The call with tenant A's identity on tenant B's resource must be a 403 or a 404, never a result. */
    public static void assertDenied(ThrowingCallable callAsTenantAOnTenantB) {
        assertThatThrownBy(callAsTenantAOnTenantB)
                .as("cross-tenant call must be refused (403) or hidden (404)")
                .isInstanceOfAny(ForbiddenActionException.class, NotFoundException.class);
    }

    /** A list returned to a tenant holds only rows owned by that tenant. */
    public static <T> void assertOnlyOwnedBy(Collection<T> rows, Function<T, String> ownerOf, String org) {
        assertThat(rows).as("rows visible to " + org).allSatisfy(r -> assertThat(ownerOf.apply(r)).isEqualTo(org));
    }
}
