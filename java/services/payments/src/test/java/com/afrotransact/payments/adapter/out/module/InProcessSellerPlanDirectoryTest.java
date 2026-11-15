package com.afrotransact.payments.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.payments.port.SellerPlanDirectory;
import com.afrotransact.seller.api.BillingPlanApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** The plan lookup is advisory, as over HTTP: no record is plan-less, a refusal is unknown. */
class InProcessSellerPlanDirectoryTest {

    private static final CallerPrincipal PAYMENTS = new CallerPrincipal("payments", "payments-service",
            Set.of("seller:billing"));

    private final BillingPlanApi api = Mockito.mock(BillingPlanApi.class);
    private final SellerPlanDirectory directory;

    InProcessSellerPlanDirectoryTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("api", api);
        beans.registerSingleton("callers", (CallerPrincipals) module -> PAYMENTS);
        directory = new InProcessSellerPlanDirectory(beans.getBeanProvider(BillingPlanApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
    }

    @Test
    void thePlanComesFromSellerAsPayments() {
        Mockito.when(api.currentPlan("org")).thenAnswer(call -> {
            assertThat(InProcessCalls.require().clientId()).isEqualTo("payments-service");
            return Optional.of(new BillingPlanApi.Plan("PRO", "ACTIVE"));
        });
        assertThat(directory.currentPlan("org")).contains(new SellerPlanDirectory.Plan("PRO", "ACTIVE"));
    }

    @Test
    void noBillingRecordIsPlanlessAndARefusalIsUnknown() {
        Mockito.when(api.currentPlan("none")).thenReturn(Optional.empty());
        assertThat(directory.currentPlan("none")).contains(new SellerPlanDirectory.Plan(null, null));
        Mockito.when(api.currentPlan("org")).thenThrow(new ModuleApiException(403, "auth.missing-permission", "no"));
        assertThat(directory.currentPlan("org")).isEmpty();
    }
}
