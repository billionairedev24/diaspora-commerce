package com.afrotransact.seller.domain;

import java.util.List;

/** Everything entered in the wizard so far; any section may be null while drafting. */
public record ApplicationData(BusinessSection business, BusinessDetailsSection businessDetails, List<Owner> owners,
                              StripeVerification stripe, PayoutSection payouts, PlanSection plan,
                              ComplianceSection compliance) {

    public static ApplicationData empty() {
        return new ApplicationData(null, null, null, null, null, null, null);
    }

    public EntityType entityType() {
        return business == null ? null : business.entityType();
    }

    public ApplicationData withBusiness(BusinessSection s) {
        return new ApplicationData(s, businessDetails, owners, stripe, payouts, plan, compliance);
    }

    public ApplicationData withBusinessDetails(BusinessDetailsSection s) {
        return new ApplicationData(business, s, owners, stripe, payouts, plan, compliance);
    }

    public ApplicationData withOwners(List<Owner> s) {
        return new ApplicationData(business, businessDetails, s == null ? null : List.copyOf(s), stripe, payouts,
                plan, compliance);
    }

    public ApplicationData withStripe(StripeVerification s) {
        return new ApplicationData(business, businessDetails, owners, s, payouts, plan, compliance);
    }

    public ApplicationData withPayouts(PayoutSection s) {
        return new ApplicationData(business, businessDetails, owners, stripe, s, plan, compliance);
    }

    public ApplicationData withPlan(PlanSection s) {
        return new ApplicationData(business, businessDetails, owners, stripe, payouts, s, compliance);
    }

    public ApplicationData withCompliance(ComplianceSection s) {
        return new ApplicationData(business, businessDetails, owners, stripe, payouts, plan, s);
    }
}
