package com.afrotransact.seller.adapter.out.module;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.payments.api.SellerBillingApi;
import com.afrotransact.seller.adapter.out.payments.DisabledEarningsWithholding;
import com.afrotransact.seller.port.ConnectedAccountRegistry;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seller's two payments ports through payments' published {@link SellerBillingApi} inside this JVM (mode {@code in-process},
 * per edge {@code at.seller.payments.mode}): the same calls as {@code PUT /v1/connected-accounts/{orgId}} and the plan fee
 * withholding endpoints, entered as seller's caller principal (scopes {@code payments:connected-accounts},
 * {@code payments:plan-fees}). Failures are the HTTP adapters' gateway failures: {@code seller.payments-failed} with the status the
 * callee answered, and, as over HTTP, a withhold refused with 422 or 409 is a plain "not withheld" and a release of a withholding
 * that does not exist (404) releases nothing. The collection kill switch ({@code at.seller.billing.collect-from-earnings}) is the
 * same.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${at.seller.payments.mode:${at.seller.mode:http}}' == 'in-process'")
class InProcessPaymentsGateways {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessPaymentsGateways.class);
    private static final String CODE = "seller.payments";

    @Bean
    ConnectedAccountRegistry sellerInProcessConnectedAccounts(ObjectProvider<SellerBillingApi> api,
                                                              ObjectProvider<CallerPrincipals> callers,
                                                              ListableBeanFactory beans) {
        SellerModuleCalls<SellerBillingApi> payments = new SellerModuleCalls<>(SellerBillingApi.class, "payments", api,
                callers, beans);
        return (orgId, stripeAccountId) -> payments.call(a -> {
            a.registerConnectedAccount(orgId, stripeAccountId);
            return null;
        }, CODE);
    }

    @Bean
    EarningsWithholdingGateway sellerInProcessEarningsWithholding(ObjectProvider<SellerBillingApi> api,
                                                                  ObjectProvider<CallerPrincipals> callers,
                                                                  ListableBeanFactory beans,
                                                                  @Value("${at.seller.billing.collect-from-earnings:false}")
                                                                  boolean collect) {
        SellerModuleCalls<SellerBillingApi> payments = new SellerModuleCalls<>(SellerBillingApi.class, "payments", api,
                callers, beans);
        if (!collect) {
            return new DisabledEarningsWithholding();
        }
        LOG.info("Plan fees may be collected from earnings (payments in-process)");
        return new EarningsWithholdingGateway() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Withholding withhold(String orgId, String invoiceId, String invoiceNumber, long amountCents,
                                        String currency) {
                try {
                    SellerBillingApi.Withholding r = payments.raw(a -> a.withhold(orgId, invoiceId, invoiceNumber,
                            amountCents, currency, "plan-fee-" + invoiceId));
                    boolean withheld = "WITHHELD".equals(r.status());
                    return new Withholding(withheld, withheld ? r.withheldMinor() : 0, r.id(), r.reason());
                } catch (ModuleApiException e) {
                    if (e.status() == 422 || e.status() == 409) {
                        return new Withholding(false, 0, null, "payments refused: " + e.status());
                    }
                    throw payments.failed(CODE, e);
                } catch (RuntimeException e) {
                    throw payments.unavailable(CODE, e);
                }
            }

            @Override
            public long release(String orgId, String invoiceId, long amountCents, String reason, String idempotencyKey) {
                try {
                    return payments.raw(a -> a.release(orgId, invoiceId, amountCents, reason, idempotencyKey));
                } catch (ModuleApiException e) {
                    if (e.status() == 404) {
                        // Nothing was withheld for this invoice: there is nothing to release.
                        return 0;
                    }
                    throw payments.failed(CODE, e);
                } catch (RuntimeException e) {
                    throw payments.unavailable(CODE, e);
                }
            }
        };
    }
}
