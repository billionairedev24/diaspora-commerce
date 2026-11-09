package com.afrotransact.taxreturns.domain.profile;

import java.util.List;

/**
 * A fact as one category uses it.
 *
 * @param definition   the question
 * @param required     whether a value is needed (when {@code requiredWhen} holds)
 * @param requiredWhen condition under which it is needed, null = always
 * @param choices      narrowed choice list for this category, empty = the definition's choices
 */
public record ProfileFact(FactDefinition definition, boolean required, Condition requiredWhen, List<String> choices) {

    public ProfileFact {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public String id() {
        return definition.id();
    }

    /** The values a CHOICE answer may take here. */
    public List<String> allowedChoices() {
        return choices.isEmpty() ? definition.choices().stream().map(FactDefinition.Choice::value).toList() : choices;
    }
}
