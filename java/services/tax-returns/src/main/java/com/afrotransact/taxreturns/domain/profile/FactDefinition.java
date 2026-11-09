package com.afrotransact.taxreturns.domain.profile;

import java.util.List;

/**
 * A typed tax question.
 *
 * @param id       stable id, e.g. {@code net_weight_oz}
 * @param label    what people see
 * @param kind     value type
 * @param unit     unit of a DECIMAL or PERCENT value, or null
 * @param owner    who supplies it
 * @param help     plain words for sellers
 * @param evidence a FILE fact that is proof for an exemption claim
 * @param choices  allowed values of a CHOICE fact
 */
public record FactDefinition(String id, String label, FactKind kind, String unit, FactOwner owner, String help,
                             boolean evidence, List<Choice> choices) {

    public FactDefinition {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /** One allowed value of a CHOICE fact. */
    public record Choice(String value, String label) {
    }
}
