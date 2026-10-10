package com.afrotransact.ai.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routing table from configuration; degrade picks the cheapest model of the route from the price table. */
public class DefaultModelRoutingPolicy implements ModelRoutingPolicy {

    private final Map<TaskType, ModelRoute> routes;
    private final PriceTable prices;

    public DefaultModelRoutingPolicy(List<ModelRoute> routes, PriceTable prices) {
        Map<TaskType, ModelRoute> byTask = new EnumMap<>(TaskType.class);
        for (ModelRoute r : routes) {
            if (byTask.put(r.task(), r) != null) {
                throw new IllegalArgumentException("duplicate route for " + r.task());
            }
        }
        for (TaskType t : TaskType.values()) {
            if (!byTask.containsKey(t)) {
                throw new IllegalArgumentException("no model route configured for task " + t);
            }
        }
        this.routes = Map.copyOf(byTask);
        this.prices = prices;
    }

    @Override
    public ModelRoute route(TaskType task) {
        return routes.get(task);
    }

    @Override
    public List<String> candidates(TaskType task, boolean degrade) {
        List<String> models = route(task).models();
        if (!degrade) {
            return models;
        }
        return prices.cheapest(models).map(List::of).orElse(models);
    }

    @Override
    public boolean shouldRetry(TaskType task, FailureKind kind, int attempt) {
        return kind.retryable() && attempt <= route(task).maxRetries();
    }
}
