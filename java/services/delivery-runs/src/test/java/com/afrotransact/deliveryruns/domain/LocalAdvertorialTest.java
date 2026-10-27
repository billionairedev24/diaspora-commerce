package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S263: an advertorial is served only while live and inside its schedule; it ends for good and keeps its reason. */
class LocalAdvertorialTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final AdvertorialCopy WORDS = new AdvertorialCopy("Bastrop run on Saturday", "Order by Thursday",
            "Order now");

    private static LocalAdvertorial generated() {
        return LocalAdvertorial.generated(UUID.randomUUID(), AdvertorialSource.RUN, UUID.randomUUID(),
                UUID.randomUUID(), WORDS, NOW);
    }

    private static LocalAdvertorial draft() {
        return LocalAdvertorial.authored(UUID.randomUUID(), AdvertorialSource.WINDOW, UUID.randomUUID(), null, "B", 3,
                7, WORDS, null, null, "staff-1", NOW);
    }

    @Test
    void aGeneratedAdvertorialIsLiveAtOnceAndFollowsItsSource() {
        LocalAdvertorial a = generated();
        assertThat(a.status()).isEqualTo(AdvertorialStatus.LIVE);
        assertThat(a.generated()).isTrue();
        assertThat(a.variant()).isEqualTo("A");
        assertThat(a.createdBy()).isEqualTo("system");
        assertThat(a.servesAt(NOW)).isTrue();

        AdvertorialCopy newer = new AdvertorialCopy("Bastrop run moved to Sunday", "Order by Friday", "Order now");
        assertThat(a.redraft(newer, NOW).copy()).isEqualTo(newer);
        assertThat(a.redraft(WORDS, NOW)).as("nothing changed").isSameAs(a);
    }

    @Test
    void staffEditsStopTheWordsFollowingTheSource() {
        LocalAdvertorial edited = generated().edit(new AdvertorialCopy("Ours", "", "Go"), "A", 2, 4, null, null, NOW);
        assertThat(edited.generated()).isFalse();
        assertThat(edited.weight()).isEqualTo(2);
        assertThat(edited.priority()).isEqualTo(4);
        assertThat(edited.redraft(new AdvertorialCopy("Source says", "", "Go"), NOW)).isSameAs(edited);
    }

    @Test
    void aDraftIsNotServedUntilPublishedAndPauseSwitchesItOff() {
        LocalAdvertorial d = draft();
        assertThat(d.servesAt(NOW)).isFalse();
        LocalAdvertorial live = d.publish(NOW);
        assertThat(live.servesAt(NOW)).isTrue();
        LocalAdvertorial paused = live.pause(NOW);
        assertThat(paused.status()).isEqualTo(AdvertorialStatus.PAUSED);
        assertThat(paused.servesAt(NOW)).isFalse();
        assertThat(paused.publish(NOW).servesAt(NOW)).isTrue();
        assertThatThrownBy(() -> d.pause(NOW)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> live.publish(NOW)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void itsOwnScheduleBoundsWhenItIsServed() {
        LocalAdvertorial a = draft().edit(WORDS, "B", 3, 7, NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(5)),
                NOW).publish(NOW);
        assertThat(a.servesAt(NOW)).isFalse();
        assertThat(a.servesAt(NOW.plus(Duration.ofHours(1)))).isTrue();
        assertThat(a.servesAt(NOW.plus(Duration.ofHours(5)))).as("the end is exclusive").isFalse();
        assertThatThrownBy(() -> draft().edit(WORDS, "B", 3, 7, NOW, NOW, NOW)).isInstanceOf(RuleViolation.class);
    }

    @Test
    void endingIsForGoodAndKeepsTheFirstReason() {
        LocalAdvertorial ended = generated().end(AdvertorialEnd.CANCELLED, NOW);
        assertThat(ended.status()).isEqualTo(AdvertorialStatus.ENDED);
        assertThat(ended.ended()).isEqualTo(AdvertorialEnd.CANCELLED);
        assertThat(ended.servesAt(NOW)).isFalse();
        assertThat(ended.end(AdvertorialEnd.STAFF, NOW).ended()).isEqualTo(AdvertorialEnd.CANCELLED);
        assertThatThrownBy(() -> ended.edit(WORDS, "A", 1, 0, null, null, NOW))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(ended.redraft(new AdvertorialCopy("New", "", "Go"), NOW)).isSameAs(ended);
    }

    @Test
    void theCreativeTokenIsWhatOrdersAreAttributedBy() {
        UUID id = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
        assertThat(LocalAdvertorial.creativeOf(id)).isEqualTo("adv-0f8fad5bd9cb469fa16570867728950e");
        assertThat(LocalAdvertorial.creativeOf(id)).matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    }

    @Test
    void wordsAndKnobsAreChecked() {
        assertThatThrownBy(() -> new AdvertorialCopy(" ", "x", "Go")).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new AdvertorialCopy("h".repeat(141), "x", "Go")).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new AdvertorialCopy("h", "b".repeat(401), "Go")).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new AdvertorialCopy("h", "b", "")).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> LocalAdvertorial.authored(UUID.randomUUID(), AdvertorialSource.RUN, UUID.randomUUID(),
                null, "A", 0, 0, WORDS, null, null, "s", NOW)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> LocalAdvertorial.authored(UUID.randomUUID(), AdvertorialSource.RUN, UUID.randomUUID(),
                null, "A", 1, 1001, WORDS, null, null, "s", NOW)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> LocalAdvertorial.authored(UUID.randomUUID(), AdvertorialSource.RUN, UUID.randomUUID(),
                null, "not a label", 1, 0, WORDS, null, null, "s", NOW)).isInstanceOf(RuleViolation.class);
    }

    @Test
    void upToFourDifferentProductsCanBeFeaturedAndTheyStayThroughTheLifecycle() {
        List<FeaturedProduct> four = List.of(new FeaturedProduct("ATSHEA0001", "Shea butter", "https://img.test/a.jpg"),
                new FeaturedProduct("ATGARI0001", "Garri", null), new FeaturedProduct("ATEGUS0001", "Egusi", null),
                new FeaturedProduct("ATPALM0001", "Palm oil", null));
        LocalAdvertorial a = LocalAdvertorial.authored(UUID.randomUUID(), AdvertorialSource.RUN, UUID.randomUUID(),
                UUID.randomUUID(), "A", 1, 0, WORDS, null, null, four, "staff-1", NOW);
        assertThat(a.featured()).containsExactlyElementsOf(four);
        assertThat(a.publish(NOW).pause(NOW).featured()).hasSize(4);
        assertThat(a.edit(WORDS, "A", 1, 0, null, null, NOW).featured()).as("an edit of the words keeps them")
                .hasSize(4);
        assertThat(a.edit(WORDS, "A", 1, 0, null, null, List.of(), NOW).featured()).isEmpty();
        assertThat(generated().featured()).isEmpty();
    }

    @Test
    void moreThanFourOrTheSameProductTwiceIsRefused() {
        List<FeaturedProduct> five = List.of(product("ATONE0001"), product("ATTWO0001"), product("ATTHREE001"),
                product("ATFOUR0001"), product("ATFIVE0001"));
        assertThatThrownBy(() -> LocalAdvertorial.authored(UUID.randomUUID(), AdvertorialSource.RUN, UUID.randomUUID(),
                null, "A", 1, 0, WORDS, null, null, five, "staff-1", NOW)).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("at most 4");
        FeaturedProduct p = product("ATONE0001");
        assertThatThrownBy(() -> LocalAdvertorial.authored(UUID.randomUUID(), AdvertorialSource.RUN, UUID.randomUUID(),
                null, "A", 1, 0, WORDS, null, null, List.of(p, p), "staff-1", NOW)).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("once");
    }

    @Test
    void aFeaturedProductNeedsACodeANameAndAnHttpsPicture() {
        assertThatThrownBy(() -> new FeaturedProduct(" ", "Garri", null)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new FeaturedProduct("ATGARI0001", "", null)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new FeaturedProduct("ATGARI0001", "Garri", "http://img.test/a.jpg"))
                .isInstanceOf(RuleViolation.class);
        assertThat(new FeaturedProduct(" ATGARI0001 ", " Garri ", " ").imageUrl()).isNull();
    }

    private static FeaturedProduct product(String atin) {
        return new FeaturedProduct(atin, "Product " + atin, null);
    }
}
