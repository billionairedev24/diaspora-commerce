package com.afrotransact.ai.application;

import java.util.List;
import java.util.function.Consumer;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.BudgetDecision;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.port.EmbeddingReply;
import com.afrotransact.ai.port.ModelReply;
import com.afrotransact.ai.port.ModelRequest;

/**
 * Every model call goes through here: budget check (allow, degrade to the cheapest model, or
 * {@code ai.budget-exceeded}), routed call with fallback, then a usage-ledger entry with the estimated cost.
 */
public interface ModelAccessService {

    /** Budget check only; throws {@code BudgetExceededException} when the caller may not use the feature today. */
    BudgetDecision admit(Caller caller, AiFeature feature);

    ModelReply chat(Caller caller, AiFeature feature, ModelRequest request);

    ModelReply stream(Caller caller, AiFeature feature, ModelRequest request, Consumer<String> onText);

    EmbeddingReply embed(Caller caller, AiFeature feature, TaskType task, List<String> inputs);
}
