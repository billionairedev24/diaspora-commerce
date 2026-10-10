package com.afrotransact.ai.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.ai.*}: the model routing table, price table, budgets and Lola settings. Model ids live only here (and in
 * the environment profiles), never in code. The OpenRouter key comes from {@code OPENROUTER_API_KEY}.
 *
 * @param routes        task (kebab-case, e.g. {@code listing-writer}) → models in fallback order, timeout, retries
 * @param prices        USD per million tokens per model
 * @param defaultPrice  price assumed for a model missing from {@code prices}
 * @param budgets       feature key (e.g. {@code lola}) → daily limits
 * @param lola          Lola agent settings
 * @param maxInputChars longest accepted chat message
 * @param embeddings    embedding settings
 */
@ConfigurationProperties(prefix = "at.ai")
public record AiProperties(Map<String, Route> routes, List<Price> prices, Price defaultPrice,
                           Map<String, Budget> budgets, Lola lola, Integer maxInputChars, Embeddings embeddings) {

    public AiProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
        prices = prices == null ? List.of() : List.copyOf(prices);
        defaultPrice = defaultPrice == null ? new Price(null, new BigDecimal("15"), new BigDecimal("75")) : defaultPrice;
        budgets = budgets == null ? Map.of() : Map.copyOf(budgets);
        lola = lola == null ? new Lola(null, null, null, null, null) : lola;
        maxInputChars = maxInputChars == null ? 4000 : maxInputChars;
        embeddings = embeddings == null ? new Embeddings(null) : embeddings;
    }

    /** One task's route. */
    public record Route(List<String> models, Duration timeout, Integer maxRetries, Duration backoff) {

        public Route {
            models = models == null ? List.of() : List.copyOf(models);
            maxRetries = maxRetries == null ? 1 : maxRetries;
        }
    }

    /** Price of one model in USD per million tokens. */
    public record Price(String model, BigDecimal input, BigDecimal output) {
    }

    /** Daily limits of one feature (0 = unlimited). */
    public record Budget(Long userDailyTokens, BigDecimal userDailyCostUsd, BigDecimal featureDailyCostUsd,
                         Boolean degradeOnExceed, Double hardLimitFactor) {

        public Budget {
            userDailyTokens = userDailyTokens == null ? 0 : userDailyTokens;
            userDailyCostUsd = userDailyCostUsd == null ? BigDecimal.ZERO : userDailyCostUsd;
            featureDailyCostUsd = featureDailyCostUsd == null ? BigDecimal.ZERO : featureDailyCostUsd;
            degradeOnExceed = degradeOnExceed == null ? Boolean.FALSE : degradeOnExceed;
            hardLimitFactor = hardLimitFactor == null ? 1.5 : hardLimitFactor;
        }
    }

    /** Lola agent settings. */
    public record Lola(Integer maxToolRounds, Integer memoryTurns, Integer searchResults, Integer maxRecipeItems,
                       Integer maxCompletionTokens) {

        public Lola {
            maxToolRounds = maxToolRounds == null ? 4 : maxToolRounds;
            memoryTurns = memoryTurns == null ? 12 : memoryTurns;
            searchResults = searchResults == null ? 6 : searchResults;
            maxRecipeItems = maxRecipeItems == null ? 25 : maxRecipeItems;
            maxCompletionTokens = maxCompletionTokens == null ? 1200 : maxCompletionTokens;
        }
    }

    /** Embedding settings: requested vector size (0 = model default; must match the search index mapping). */
    public record Embeddings(Integer dimensions) {

        public Embeddings {
            dimensions = dimensions == null ? 0 : dimensions;
        }
    }
}
