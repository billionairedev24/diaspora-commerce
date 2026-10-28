package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.BusinessDetailsInput;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.ComplianceSection;
import com.afrotransact.seller.domain.Owner;
import com.afrotransact.seller.domain.PlanSection;

/**
 * Data of one wizard step; only the field matching the saved step is read. Stripe verification and payouts have no
 * input: Stripe's embedded components collect them.
 */
public record StepInput(BusinessSection business, BusinessDetailsInput businessDetails, List<Owner> owners,
                        PlanSection plan, ComplianceSection compliance) {

    public static StepInput business(BusinessSection s) {
        return new StepInput(s, null, null, null, null);
    }

    public static StepInput businessDetails(BusinessDetailsInput s) {
        return new StepInput(null, s, null, null, null);
    }

    public static StepInput owners(List<Owner> s) {
        return new StepInput(null, null, s, null, null);
    }

    public static StepInput plan(PlanSection s) {
        return new StepInput(null, null, null, s, null);
    }

    public static StepInput compliance(ComplianceSection s) {
        return new StepInput(null, null, null, null, s);
    }

    public static StepInput none() {
        return new StepInput(null, null, null, null, null);
    }
}
