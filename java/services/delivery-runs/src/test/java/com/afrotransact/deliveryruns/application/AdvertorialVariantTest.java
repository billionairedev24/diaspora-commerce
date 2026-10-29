package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AdvertorialCopy;
import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.LocalAdvertorial;
import org.junit.jupiter.api.Test;

/** S263: A/B variants share a source's visitors by weight, and a visitor always gets the same one. */
class AdvertorialVariantTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final UUID SOURCE = UUID.randomUUID();

    private static LocalAdvertorial variant(String label, int weight) {
        return LocalAdvertorial.authored(UUID.randomUUID(), AdvertorialSource.WINDOW, SOURCE, null, label, weight, 0,
                new AdvertorialCopy("Headline " + label, "", "Go"), null, null, "staff", NOW).publish(NOW);
    }

    @Test
    void aVisitorKeepsTheirVariant() {
        List<LocalAdvertorial> variants = List.of(variant("A", 1), variant("B", 1));
        for (int i = 0; i < 50; i++) {
            String visitor = "visitor-" + i;
            LocalAdvertorial first = AdvertorialServingImpl.pick(variants, visitor, SOURCE);
            assertThat(AdvertorialServingImpl.pick(variants, visitor, SOURCE).id()).isEqualTo(first.id());
        }
    }

    @Test
    void theShareFollowsTheWeights() {
        List<LocalAdvertorial> variants = List.of(variant("A", 1), variant("B", 3));
        int b = 0;
        int visitors = 4000;
        for (int i = 0; i < visitors; i++) {
            if (AdvertorialServingImpl.pick(variants, "v" + i, SOURCE).variant().equals("B")) {
                b++;
            }
        }
        assertThat(b / (double) visitors).as("B gets about three quarters").isBetween(0.70, 0.80);
    }

    @Test
    void oneVariantAlwaysWinsAndAnonymousVisitorsStillGetOne() {
        LocalAdvertorial only = variant("A", 5);
        assertThat(AdvertorialServingImpl.pick(List.of(only), "x", SOURCE)).isSameAs(only);
        Set<String> seen = new HashSet<>();
        List<LocalAdvertorial> two = List.of(variant("A", 1), variant("B", 1));
        for (int i = 0; i < 200; i++) {
            seen.add(AdvertorialServingImpl.pick(two, null, SOURCE).variant());
        }
        assertThat(seen).containsExactlyInAnyOrder("A", "B");
    }
}
