package com.afrotransact.ai.application;

import java.util.EnumMap;
import java.util.Map;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.BudgetLimits;

/**
 * Application settings built from {@code at.ai.*}.
 *
 * @param budgets            daily limits per feature (missing = unlimited)
 * @param maxToolRounds      Lola: model rounds that may call tools before she must answer
 * @param memoryTurns        Lola: stored turns replayed into the prompt
 * @param searchResults      Lola: hits per search_products call
 * @param maxRecipeItems     Lola: ingredients resolved per build_cart_from_recipe call
 * @param maxInputChars      longest accepted user input
 * @param maxCompletionTokens completion limit for Lola rounds
 * @param embeddingDimensions requested embedding dimensions (0 = model default)
 */
public record AiSettings(Map<AiFeature, BudgetLimits> budgets, int maxToolRounds, int memoryTurns, int searchResults,
                         int maxRecipeItems, int maxInputChars, int maxCompletionTokens, int embeddingDimensions) {

    public AiSettings {
        Map<AiFeature, BudgetLimits> copy = new EnumMap<>(AiFeature.class);
        if (budgets != null) {
            copy.putAll(budgets);
        }
        budgets = Map.copyOf(copy);
    }

    public BudgetLimits limits(AiFeature feature) {
        return budgets.getOrDefault(feature, BudgetLimits.unlimited());
    }
}
