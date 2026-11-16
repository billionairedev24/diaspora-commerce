package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.ModuleApiException;
import org.junit.jupiter.api.Test;

/** The {@code @StaffOrServiceOnly} guard for an API method entered for a person: staff and the super admin pass, nobody else. */
class ModuleEntryRequireStaffTest {

    private static ModuleEntry entryFor(PrincipalType type, String... permissions) {
        return new ModuleEntry(() -> new Actor("u-1", type, Optional.empty(), Set.of(), Set.of(permissions)));
    }

    @Test
    void staffPasses() {
        assertThatCode(entryFor(PrincipalType.STAFF)::requireStaff).doesNotThrowAnyException();
    }

    @Test
    void theSuperAdminWildcardPasses() {
        assertThatCode(entryFor(PrincipalType.CUSTOMER, "*")::requireStaff).doesNotThrowAnyException();
    }

    @Test
    void aSellerMemberOrCustomerIsRefusedWith403StaffOnly() {
        for (PrincipalType type : new PrincipalType[] {PrincipalType.SELLER_MEMBER, PrincipalType.CUSTOMER}) {
            assertThatThrownBy(entryFor(type, "catalog:taxonomy")::requireStaff).isInstanceOfSatisfying(ModuleApiException.class,
                    e -> {
                        assertThat(e.status()).isEqualTo(403);
                        assertThat(e.code()).isEqualTo("auth.staff-only");
                    });
        }
    }
}
