package com.afrotransact.seller.domain;

import java.util.List;

/**
 * The form Account health renders for one Stripe requirement.
 *
 * @param subject {@code company}, {@code individual}, {@code representative}, a person id, or {@code account}
 */
public record RequirementForm(String requirement, RequirementKind kind, String label, String subject, String why,
                              List<FormField> fields) {

    public RequirementForm {
        fields = List.copyOf(fields);
    }
}
