package com.afrotransact.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.BudgetDecision;
import com.afrotransact.ai.domain.BudgetExceededException;
import com.afrotransact.ai.domain.BudgetPolicy;
import com.afrotransact.ai.domain.PriceTable;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.domain.UsageTotals;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.EmbeddingReply;
import com.afrotransact.ai.port.ModelReply;
import com.afrotransact.ai.port.ModelRequest;
import com.afrotransact.ai.port.ModelRouter;
import com.afrotransact.ai.port.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class ModelAccessServiceImpl implements ModelAccessService {

    private static final Logger LOG = LoggerFactory.getLogger(ModelAccessServiceImpl.class);

    private final ModelRouter router;
    private final UsageLedger ledger;
    private final BudgetPolicy budgets;
    private final PriceTable prices;
    private final AiSettings settings;
    private final AiMetrics metrics;
    private final Clock clock;

    ModelAccessServiceImpl(ModelRouter router, UsageLedger ledger, BudgetPolicy budgets, PriceTable prices,
                           AiSettings settings, AiMetrics metrics, Clock clock) {
        this.router = router;
        this.ledger = ledger;
        this.budgets = budgets;
        this.prices = prices;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public BudgetDecision admit(Caller caller, AiFeature feature) {
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        UsageTotals user = ledger.userTotals(caller.subject(), feature.key(), today);
        UsageTotals all = ledger.featureTotals(feature.key(), today);
        BudgetDecision decision = budgets.decide(user, all, settings.limits(feature));
        metrics.budget(feature.key(), decision.verdict().name().toLowerCase(Locale.ROOT));
        if (decision.denied()) {
            LOG.info("AI budget exceeded: feature={} scope={}", feature.key(), decision.scope());
            throw new BudgetExceededException(feature.key(), decision.scope(), decision.detail());
        }
        return decision;
    }

    @Override
    public ModelReply chat(Caller caller, AiFeature feature, ModelRequest request) {
        BudgetDecision decision = admit(caller, feature);
        ModelReply reply = router.chat(request.withDegrade(decision.degrade()));
        record(caller, feature, request.task(), reply.model(), reply.usage(), decision.degrade(), reply.fellBack());
        return reply;
    }

    @Override
    public ModelReply stream(Caller caller, AiFeature feature, ModelRequest request, Consumer<String> onText) {
        BudgetDecision decision = admit(caller, feature);
        ModelReply reply = router.stream(request.withDegrade(decision.degrade()), onText);
        record(caller, feature, request.task(), reply.model(), reply.usage(), decision.degrade(), reply.fellBack());
        return reply;
    }

    @Override
    public EmbeddingReply embed(Caller caller, AiFeature feature, TaskType task, List<String> inputs) {
        BudgetDecision decision = admit(caller, feature);
        EmbeddingReply reply = router.embed(task, inputs, decision.degrade());
        record(caller, feature, task, reply.model(), reply.usage(), decision.degrade(), reply.attempts() > 1);
        return reply;
    }

    private void record(Caller caller, AiFeature feature, TaskType task, String model, TokenUsage usage,
                        boolean degraded, boolean fallback) {
        long cost = prices.costMicros(model, usage);
        ledger.record(new UsageLedger.Entry(caller.subject(), feature.key(), task.name(), model, usage, cost, degraded,
                fallback, Instant.now(clock)));
        metrics.usage(task.name(), model, usage, cost);
    }
}
