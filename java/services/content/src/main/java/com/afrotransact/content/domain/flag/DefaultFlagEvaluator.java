package com.afrotransact.content.domain.flag;

/**
 * Evaluation order: kill switch → disabled → allow-list → market → role → rollout. A partial rollout needs a
 * subject; anonymous evaluations without one get the default (off).
 */
public class DefaultFlagEvaluator implements FlagEvaluator {

    private final Bucketing bucketing;

    public DefaultFlagEvaluator(Bucketing bucketing) {
        this.bucketing = bucketing;
    }

    @Override
    public FlagEvaluation evaluate(FeatureFlag flag, EvaluationContext ctx) {
        if (flag.killSwitch() || !flag.enabled()) {
            return FlagEvaluation.of(flag, false, FlagReason.DISABLED);
        }
        FlagTargeting t = flag.targeting();
        if (ctx.subject() != null && t.allowList().contains(ctx.subject())) {
            return FlagEvaluation.of(flag, true, FlagReason.TARGETING_MATCH);
        }
        if (!t.markets().isEmpty() && (ctx.market() == null || !t.markets().contains(ctx.market()))) {
            return FlagEvaluation.of(flag, false, FlagReason.DEFAULT);
        }
        if (!t.roles().isEmpty() && ctx.roles().stream().noneMatch(t.roles()::contains)) {
            return FlagEvaluation.of(flag, false, FlagReason.DEFAULT);
        }
        boolean targeted = !t.markets().isEmpty() || !t.roles().isEmpty();
        int bp = flag.rolloutBasisPoints();
        if (bp >= Bucketing.BUCKETS) {
            return FlagEvaluation.of(flag, true, targeted ? FlagReason.TARGETING_MATCH : FlagReason.STATIC);
        }
        if (bp <= 0 || ctx.subject() == null) {
            return FlagEvaluation.of(flag, false, FlagReason.DEFAULT);
        }
        boolean in = bucketing.bucket(flag.key(), ctx.subject()) < bp;
        return FlagEvaluation.of(flag, in, FlagReason.SPLIT);
    }
}
