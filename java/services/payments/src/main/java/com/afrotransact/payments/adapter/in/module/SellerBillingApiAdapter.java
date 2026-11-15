package com.afrotransact.payments.adapter.in.module;

import com.afrotransact.payments.api.SellerBillingApi;
import com.afrotransact.payments.application.ConnectedAccountService;
import com.afrotransact.payments.application.PlanFeeService;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link SellerBillingApi}: the use cases behind {@code PUT /v1/connected-accounts/{orgId}} and the plan
 * fee withholding endpoints, entered under the calling module's principal. The application services apply the permission checks
 * ({@code payments:connected-accounts}, {@code payments:plan-fees}) exactly as for a token.
 */
@Component
class SellerBillingApiAdapter implements SellerBillingApi {

    private final ConnectedAccountService accounts;
    private final PlanFeeService planFees;
    private final ModuleEntry entry;

    SellerBillingApiAdapter(ConnectedAccountService accounts, PlanFeeService planFees, ActorResolver actors) {
        this.accounts = accounts;
        this.planFees = planFees;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public void registerConnectedAccount(String orgId, String stripeAccountId) {
        entry.runVoid(() -> accounts.register(orgId, stripeAccountId), PaymentsFailures::map);
    }

    @Override
    public Withholding withhold(String orgId, String invoiceId, String invoiceNumber, long amountMinor, String currency,
                                String idempotencyKey) {
        return entry.run(() -> {
            var w = planFees.withhold(orgId, new PlanFeeService.PlanFeeCommand(invoiceId, invoiceNumber, amountMinor,
                    currency), idempotencyKey);
            return new Withholding(String.valueOf(w.id()), w.status().name(), w.withheld().minor(), w.reason());
        }, PaymentsFailures::map);
    }

    @Override
    public long release(String orgId, String invoiceId, long amountMinor, String reason, String idempotencyKey) {
        return entry.run(() -> planFees.release(orgId, invoiceId, amountMinor, reason, idempotencyKey).released().minor(),
                PaymentsFailures::map);
    }
}
