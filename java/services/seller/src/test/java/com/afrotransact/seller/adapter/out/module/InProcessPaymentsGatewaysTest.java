package com.afrotransact.seller.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.payments.api.SellerBillingApi;
import com.afrotransact.seller.port.ConnectedAccountRegistry;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Seller's payments edge keeps the HTTP adapters' answers: 422 and 409 are "not withheld", 404 releases nothing, the rest fail. */
class InProcessPaymentsGatewaysTest {

    private static final CallerPrincipal SELLER = new CallerPrincipal("seller", "seller-service",
            Set.of("payments:plan-fees"));

    private static StaticListableBeanFactory beans(SellerBillingApi api) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("api", api);
        beans.addBean("callers", (CallerPrincipals) module -> SELLER);
        return beans;
    }

    private static EarningsWithholdingGateway gateway(SellerBillingApi api) {
        StaticListableBeanFactory beans = beans(api);
        return new InProcessPaymentsGateways().sellerInProcessEarningsWithholding(
                beans.getBeanProvider(SellerBillingApi.class), beans.getBeanProvider(CallerPrincipals.class), beans, true);
    }

    @Test
    void withholdingAsSellerAndTheKillSwitch() {
        SellerBillingApi api = new Fake() {
            @Override
            public Withholding withhold(String org, String invoice, String number, long amount, String currency, String key) {
                assertThat(InProcessCalls.require()).isEqualTo(SELLER);
                assertThat(key).isEqualTo("plan-fee-inv-1");
                return new Withholding("w1", "WITHHELD", 700, null);
            }
        };
        EarningsWithholdingGateway.Withholding w = gateway(api).withhold("org", "inv-1", "N-1", 1000, "USD");
        assertThat(w.withheld()).isTrue();
        assertThat(w.withheldCents()).isEqualTo(700);
        StaticListableBeanFactory beans = beans(api);
        assertThat(new InProcessPaymentsGateways().sellerInProcessEarningsWithholding(
                beans.getBeanProvider(SellerBillingApi.class), beans.getBeanProvider(CallerPrincipals.class), beans, false)
                .enabled()).isFalse();
    }

    @Test
    void aRefusedWithholdIsPlainNotWithheldAndOtherStatusesFail() {
        SellerBillingApi api = new Fake() {
            @Override
            public Withholding withhold(String org, String invoice, String number, long amount, String currency, String key) {
                if ("inv-conflict".equals(invoice)) {
                    throw new ModuleApiException(409, "payments.idempotency-conflict", "other amount");
                }
                if ("inv-forbidden".equals(invoice)) {
                    throw new ModuleApiException(403, "auth.missing-permission", "no scope");
                }
                throw new IllegalStateException("down");
            }
        };
        EarningsWithholdingGateway gateway = gateway(api);
        assertThat(gateway.withhold("org", "inv-conflict", null, 1, "USD").reason()).isEqualTo("payments refused: 409");
        assertThatThrownBy(() -> gateway.withhold("org", "inv-forbidden", null, 1, "USD")).isInstanceOfSatisfying(
                GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo("seller.payments-failed");
                    assertThat(e.rejected()).isTrue();
                });
        assertThatThrownBy(() -> gateway.withhold("org", "inv-down", null, 1, "USD")).isInstanceOfSatisfying(
                GatewayException.class, e -> assertThat(e.code()).isEqualTo("seller.payments-unavailable"));
    }

    @Test
    void releasingWhatWasNeverWithheldReleasesNothing() {
        SellerBillingApi api = new Fake() {
            @Override
            public long release(String org, String invoice, long amount, String reason, String key) {
                throw new ModuleApiException(404, "payments.withholding-not-found", "none");
            }
        };
        assertThat(gateway(api).release("org", "inv-1", 5, "void", "k")).isZero();
    }

    @Test
    void aConnectedAccountFailureIsThePaymentsGatewayFailure() {
        SellerBillingApi api = new Fake() {
            @Override
            public void registerConnectedAccount(String orgId, String stripeAccountId) {
                throw new ModuleApiException(422, "payments.invalid", "bad");
            }
        };
        StaticListableBeanFactory beans = beans(api);
        ConnectedAccountRegistry registry = new InProcessPaymentsGateways().sellerInProcessConnectedAccounts(
                beans.getBeanProvider(SellerBillingApi.class), beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThatThrownBy(() -> registry.register("org", "acct")).isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.code()).isEqualTo("seller.payments-failed"));
    }

    @Test
    void withoutThePaymentsModuleTheStartFailsNamingBoth() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        assertThatThrownBy(() -> new InProcessPaymentsGateways().sellerInProcessConnectedAccounts(
                empty.getBeanProvider(SellerBillingApi.class), empty.getBeanProvider(CallerPrincipals.class), empty))
                .hasMessageContaining("'seller'").hasMessageContaining("'payments'")
                .hasMessageContaining("at.seller.payments.mode");
    }

    private static class Fake implements SellerBillingApi {
        @Override
        public void registerConnectedAccount(String orgId, String stripeAccountId) {
        }

        @Override
        public Withholding withhold(String orgId, String invoiceId, String invoiceNumber, long amountMinor,
                                    String currency, String idempotencyKey) {
            return null;
        }

        @Override
        public long release(String orgId, String invoiceId, long amountMinor, String reason, String idempotencyKey) {
            return 0;
        }
    }
}
