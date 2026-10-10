package com.afrotransact.content.domain.flag;

/** Evaluates one flag for one context. */
public interface FlagEvaluator {

    FlagEvaluation evaluate(FeatureFlag flag, EvaluationContext ctx);
}
