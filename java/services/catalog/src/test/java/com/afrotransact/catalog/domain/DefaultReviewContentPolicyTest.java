package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultReviewContentPolicyTest {

    private final ReviewContentPolicy policy = new DefaultReviewContentPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
        "Lovely jollof spice, the whole family enjoyed it. Arrived in two days.",
        "Les plantains étaient parfaits, très frais.",
        "Compré el aceite de palma, llegó bien empaquetado y sabe auténtico.",
        "Night and day compared with the last brand; made in Niger and Ghana. 10/10 would buy again!",
        "Scunthorpe classic, the cocktail glass set is great and the assassin's creed poster too",
        "Paid 2000 for 3 bags, 5 stars"})
    void cleanTextPassesTheChecks(String text) {
        assertThat(policy.screen("Great product", text)).isEmpty();
    }

    @Test
    void nothingToScreenIsClean() {
        assertThat(policy.screen(null, null)).isEmpty();
        assertThat(policy.screen("", " ")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"This is shit", "what a SH1T product", "fuuuuck this", "Total merde", "una mierda",
        "$hit", "You bastards", "putain de produit"})
    void profanityIsFoundInEveryDisguise(String text) {
        assertThat(policy.screen(null, text)).contains(ReviewFlag.PROFANITY);
    }

    @Test
    void profanityInTheTitleCountsToo() {
        assertThat(policy.screen("Bullshit", "ok")).containsExactly(ReviewFlag.PROFANITY);
    }

    @Test
    void shortWordsOnlyMatchWhole() {
        assertThat(policy.screen(null, "The dickens novel, a cocktail and an assistant")).isEmpty();
        assertThat(policy.screen(null, "what a dick")).containsExactly(ReviewFlag.PROFANITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"visit https://cheap-deals.example/buy now", "see www.shop-here.net", "buy at mystore.com",
        "order on deals.shop today"})
    void linksAndDomainsAreFlagged(String text) {
        assertThat(policy.screen(null, text)).contains(ReviewFlag.LINK);
    }

    @Test
    void emailsAreFlaggedAsEmailsNotAsLinks() {
        assertThat(policy.screen(null, "write to ada.obi@gmail.com for a discount")).containsExactly(ReviewFlag.EMAIL);
        assertThat(policy.screen(null, "ada (at) gmail.com")).contains(ReviewFlag.EMAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"call me on 0803 123 4567", "WhatsApp +234 803 123 4567", "text 713-555-0142 for more",
        "(713) 555 0142", "07031234567"})
    void phoneNumbersAreFlagged(String text) {
        assertThat(policy.screen(null, text)).contains(ReviewFlag.PHONE);
    }

    @Test
    void shortNumbersAreNotPhoneNumbers() {
        assertThat(policy.screen(null, "Order 12345 arrived, paid 19.99 for 500 g")).isEmpty();
    }

    @Test
    void severalFindingsAreAllReportedInAStableOrder() {
        List<ReviewFlag> flags = policy.screen("shit", "mail a@b.co or see www.x.org or call 713 555 0142");
        assertThat(flags).containsExactly(ReviewFlag.PROFANITY, ReviewFlag.EMAIL, ReviewFlag.LINK, ReviewFlag.PHONE);
    }

    @Test
    void operatorWordsAreBlockedWhole() {
        ReviewContentPolicy custom = new DefaultReviewContentPolicy(List.of("Acme", " "));
        assertThat(custom.screen(null, "Acme is better")).containsExactly(ReviewFlag.PROFANITY);
        assertThat(custom.screen(null, "Acmeville is a town")).isEmpty();
    }
}
