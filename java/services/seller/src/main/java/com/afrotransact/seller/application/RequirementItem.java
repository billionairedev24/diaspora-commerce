package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.RequirementError;
import com.afrotransact.seller.domain.RequirementForm;

/**
 * One thing Stripe needs, with its form.
 *
 * @param bucket PAST_DUE, CURRENTLY_DUE, EVENTUALLY_DUE or PENDING_VERIFICATION
 */
public record RequirementItem(String requirement, String bucket, RequirementForm form,
                              List<RequirementError> errors) {

    public RequirementItem {
        errors = List.copyOf(errors);
    }
}
