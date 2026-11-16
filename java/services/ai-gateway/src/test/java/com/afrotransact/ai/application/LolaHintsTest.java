package com.afrotransact.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.ai.port.ToolSpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Lola's hints show what she can do; they carry no festival names (owner decision). */
class LolaHintsTest {

    private static final Pattern FESTIVALS = Pattern.compile(
            "\\b(diwali|divali|deepavali|eid|ramadan|holi|hanukkah|diya|diyas)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void openingChipsAreCapabilityHintsWithoutFestivals() {
        assertThat(LolaSuggestions.OPENING).contains("Track my order", "Jollof for 20", "Delivery to my ZIP code",
                "Find a product", "Book knotless braids", "Compare prices");
        assertThat(LolaSuggestions.OPENING).noneMatch(s -> FESTIVALS.matcher(s).find());
    }

    @Test
    void everySuggestionSetIsFestivalFree() {
        List<String> all = new ArrayList<>(LolaSuggestions.OPENING);
        for (String tool : List.of(LolaToolSpecs.BUILD_CART_FROM_RECIPE, LolaToolSpecs.TRACK_ORDER,
                LolaToolSpecs.FIND_SERVICE, LolaToolSpecs.SEARCH_PRODUCTS)) {
            all.addAll(LolaSuggestions.after(Set.of(tool), false));
        }
        all.addAll(LolaSuggestions.after(Set.of(), true));
        assertThat(all).isNotEmpty().noneMatch(s -> FESTIVALS.matcher(s).find());
    }

    @Test
    void promptAndToolDescriptionsHaveNoFestivalExamples() {
        assertThat(LolaPrompts.system(LocalDate.of(2026, 11, 16), "77002", "en")).doesNotContainPattern(FESTIVALS);
        for (ToolSpec spec : LolaToolSpecs.ALL) {
            assertThat(spec.description() + spec.inputSchema()).as(spec.name()).doesNotContainPattern(FESTIVALS);
        }
    }
}
