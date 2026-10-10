package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.stream.IntStream;

import com.afrotransact.content.domain.flag.DefaultFlagEvaluator;
import com.afrotransact.content.domain.flag.EvaluationContext;
import com.afrotransact.content.domain.flag.FeatureFlag;
import com.afrotransact.content.domain.flag.FlagEvaluation;
import com.afrotransact.content.domain.flag.FlagReason;
import com.afrotransact.content.domain.flag.FlagTargeting;
import com.afrotransact.content.domain.flag.Murmur3Bucketing;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlagEvaluatorTest {

    private final Murmur3Bucketing bucketing = new Murmur3Bucketing();
    private final DefaultFlagEvaluator evaluator = new DefaultFlagEvaluator(bucketing);

    @Test
    void murmur3MatchesReferenceVectors() {
        assertThat(Murmur3Bucketing.murmur3x86(new byte[0], 0)).isZero();
        assertThat(Murmur3Bucketing.murmur3x86(new byte[0], 1)).isEqualTo(0x514E28B7);
        assertThat(Murmur3Bucketing.murmur3x86(new byte[0], 0xffffffff)).isEqualTo(0x81F16F39);
        assertThat(Murmur3Bucketing.murmur3x86(new byte[] {0, 0, 0, 0}, 0)).isEqualTo(0x2362F9DE);
        assertThat(Murmur3Bucketing.murmur3x86(
                "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8), 0)).isEqualTo(0x2E4FF723);
    }

    @Test
    void aSubjectStaysInTheSameBucket() {
        FeatureFlag flag = flag(37.5, FlagTargeting.none());
        for (int i = 0; i < 200; i++) {
            String subject = "user-" + i;
            FlagEvaluation first = evaluator.evaluate(flag, ctx(subject));
            assertThat(evaluator.evaluate(flag, ctx(subject))).isEqualTo(first);
            assertThat(bucketing.bucket("checkout.express-pay", subject))
                    .isEqualTo(bucketing.bucket("checkout.express-pay", subject)).isBetween(0, 9999);
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 10, 25, 50, 90})
    void rolloutDistributionIsCloseToThePercentage(double percentage) {
        FeatureFlag flag = flag(percentage, FlagTargeting.none());
        long on = IntStream.range(0, 10_000).filter(i -> evaluator.evaluate(flag, ctx("subject-" + i)).value()).count();
        assertThat(on / 100.0).isCloseTo(percentage, Offset.offset(1.5));
    }

    @Test
    void raisingTheRolloutOnlyAddsSubjects() {
        FeatureFlag ten = flag(10, FlagTargeting.none());
        FeatureFlag thirty = flag(30, FlagTargeting.none());
        IntStream.range(0, 5_000).mapToObj(i -> "s" + i)
                .filter(s -> evaluator.evaluate(ten, ctx(s)).value())
                .forEach(s -> assertThat(evaluator.evaluate(thirty, ctx(s)).value()).isTrue());
    }

    @Test
    void killSwitchAndDisabledWinOverEverything() {
        FeatureFlag allowed = flag(100, new FlagTargeting(Set.of(), Set.of(), Set.of("qa-1")));
        assertThat(evaluator.evaluate(allowed.withKillSwitch(true, "ops", Instant.EPOCH), ctx("qa-1")))
                .extracting(FlagEvaluation::value, FlagEvaluation::reason).containsExactly(false, FlagReason.DISABLED);
        FeatureFlag disabled = new FeatureFlag("f.x", "d", false, 100, false, FlagTargeting.none(), 0, Instant.EPOCH, "x");
        assertThat(evaluator.evaluate(disabled, ctx("u")).reason()).isEqualTo(FlagReason.DISABLED);
    }

    @Test
    void targetingRules() {
        FeatureFlag f = flag(0, new FlagTargeting(Set.of("us"), Set.of("seller.owner"), Set.of("beta-tester")));
        assertThat(evaluator.evaluate(f, new EvaluationContext("beta-tester", "CA", Set.of())))
                .extracting(FlagEvaluation::value, FlagEvaluation::reason).containsExactly(true, FlagReason.TARGETING_MATCH);
        assertThat(evaluator.evaluate(f, new EvaluationContext("u", "CA", Set.of("seller.owner"))).value()).isFalse();
        FeatureFlag full = flag(100, new FlagTargeting(Set.of("US"), Set.of("seller.owner"), Set.of()));
        assertThat(evaluator.evaluate(full, new EvaluationContext("u", "us", Set.of("seller.owner"))))
                .extracting(FlagEvaluation::value, FlagEvaluation::reason).containsExactly(true, FlagReason.TARGETING_MATCH);
        assertThat(evaluator.evaluate(full, new EvaluationContext("u", "US", Set.of("customer"))).value()).isFalse();
        assertThat(evaluator.evaluate(flag(100, FlagTargeting.none()), ctx(null)).reason()).isEqualTo(FlagReason.STATIC);
        assertThat(evaluator.evaluate(flag(50, FlagTargeting.none()), ctx(null)))
                .extracting(FlagEvaluation::value, FlagEvaluation::reason).containsExactly(false, FlagReason.DEFAULT);
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> new FeatureFlag("Bad Key", "", true, 101, false, null, 0, Instant.EPOCH, "x").validate())
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKeys("key", "description", "rolloutPercentage"));
    }

    private static FeatureFlag flag(double pct, FlagTargeting targeting) {
        return new FeatureFlag("checkout.express-pay", "Express pay", true, pct, false, targeting, 3, Instant.EPOCH, "x");
    }

    private static EvaluationContext ctx(String subject) {
        return new EvaluationContext(subject, "US", Set.of());
    }
}
