package com.afrotransact.payments.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.payments.port.OrgMembershipUnavailableException;
import com.afrotransact.seller.api.OrgMembershipApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Payments calls seller as its own principal and refuses the money action when seller does not give a clear answer. */
class InProcessOrgMembershipDirectoryTest {

    private static final CallerPrincipal PAYMENTS = new CallerPrincipal("payments", "payments-service",
            Set.of("sellers:membership.read"));

    private static ObjectProvider<CallerPrincipals> callers() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("callers", (CallerPrincipals) module -> PAYMENTS);
        return beans.getBeanProvider(CallerPrincipals.class);
    }

    private static ObjectProvider<OrgMembershipApi> api(OrgMembershipApi api) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("api", api);
        return beans.getBeanProvider(OrgMembershipApi.class);
    }

    @Test
    void callsSellerUnderPaymentsPrincipal() {
        InProcessOrgMembershipDirectory directory = new InProcessOrgMembershipDirectory(api((org, user) -> {
            assertThat(InProcessCalls.require()).isEqualTo(PAYMENTS);
            return Optional.of("seller.owner");
        }), callers());
        assertThat(directory.enabled()).isTrue();
        assertThat(directory.roleOf("org", "u1")).contains("seller.owner");
    }

    @Test
    void aRefusalOrFailureIsUnavailableNeverAllowed() {
        InProcessOrgMembershipDirectory directory = new InProcessOrgMembershipDirectory(api((org, user) -> {
            throw new IllegalStateException("403");
        }), callers());
        assertThatThrownBy(() -> directory.roleOf("org", "u1")).isInstanceOf(OrgMembershipUnavailableException.class);
    }
}
