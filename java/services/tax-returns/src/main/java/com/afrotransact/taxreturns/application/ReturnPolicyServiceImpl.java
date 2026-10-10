package com.afrotransact.taxreturns.application;

import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;
import com.afrotransact.taxreturns.port.SellerPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReturnPolicyServiceImpl implements ReturnPolicyService {

    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int MAX_FEE_BPS = 2000;

    private final SellerPolicyRepository policies;
    private final Access access;

    ReturnPolicyServiceImpl(SellerPolicyRepository policies, Access access) {
        this.policies = policies;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public SellerReturnPolicy get(String orgId) {
        access.orgOrStaff(TaxReturnsPermissions.RETURNS_MANAGE, orgId);
        return policies.find(orgId).orElse(new SellerReturnPolicy(orgId, DEFAULT_WINDOW_DAYS, 0, true, false, null,
                -1L));
    }

    @Override
    @Transactional
    public SellerReturnPolicy put(SellerReturnPolicy policy) {
        access.orgOrStaff(TaxReturnsPermissions.RETURNS_MANAGE, policy.orgId());
        if (policy.restockingFeeBps() > MAX_FEE_BPS) {
            throw new ReturnRuleViolation("returns.restocking-fee-too-high", "Restocking fees are capped at 20%");
        }
        if (policy.windowDays() < 14) {
            throw new ReturnRuleViolation("returns.window-too-short", "Return windows are at least 14 days");
        }
        if (policy.returnAddress() != null && !policy.returnAddress().complete()) {
            throw new ReturnRuleViolation("returns.address-incomplete", "Street, city and ZIP are required");
        }
        return policies.upsert(policy);
    }
}
