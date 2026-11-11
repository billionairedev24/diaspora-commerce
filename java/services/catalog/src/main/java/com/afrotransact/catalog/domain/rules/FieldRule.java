package com.afrotransact.catalog.domain.rules;

import java.util.Objects;

/**
 * One field of the matrix for one kind.
 *
 * @param field  the field
 * @param group  the matrix group (Identity, Content, Offer ...)
 * @param label  the matrix's plain name of the field
 * @param status what the field is for this kind
 * @param note   the matrix note for the field (plain words); empty when none
 */
public record FieldRule(RuleField field, String group, String label, FieldStatus status, String note) {

    public FieldRule {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(status, "status");
        group = group == null ? "" : group;
        label = label == null ? field.id() : label;
        note = note == null ? "" : note;
    }
}
