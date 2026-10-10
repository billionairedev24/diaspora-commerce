package com.afrotransact.ai.application;

import java.util.List;
import java.util.Set;

/** Suggestion chips shown above the chat input, chosen from what Lola just did. */
final class LolaSuggestions {

    static final List<String> OPENING = List.of("Jollof for 20", "Diwali dinner for 8", "Track my order",
            "Find a braider near me", "Gift ideas under $50");

    private LolaSuggestions() {
    }

    static List<String> after(Set<String> toolsUsed, boolean handedOff) {
        if (handedOff) {
            return List.of("Track my order", "Start a return");
        }
        if (toolsUsed.contains(LolaToolSpecs.BUILD_CART_FROM_RECIPE)) {
            return List.of("Make it for more people", "Swap for cheaper brands", "Go to checkout");
        }
        if (toolsUsed.contains(LolaToolSpecs.TRACK_ORDER) || toolsUsed.contains(LolaToolSpecs.START_RETURN)) {
            return List.of("Start a return", "Talk to support", "Buy it again");
        }
        if (toolsUsed.contains(LolaToolSpecs.FIND_SERVICE) || toolsUsed.contains(LolaToolSpecs.BOOK_SERVICE)) {
            return List.of("Show more pros", "Check availability this weekend");
        }
        if (toolsUsed.contains(LolaToolSpecs.SEARCH_PRODUCTS) || toolsUsed.contains(LolaToolSpecs.COMPARE_OFFERS)
                || toolsUsed.contains(LolaToolSpecs.ADD_TO_CART)) {
            return List.of("Show cheaper options", "Check delivery time", "Build a recipe cart");
        }
        return OPENING;
    }
}
