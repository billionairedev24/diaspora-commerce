package com.afrotransact.ai.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.afrotransact.ai.application.AiSettings;
import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.BudgetLimits;
import com.afrotransact.ai.domain.BudgetPolicy;
import com.afrotransact.ai.domain.DefaultBudgetPolicy;
import com.afrotransact.ai.domain.DefaultModelRoutingPolicy;
import com.afrotransact.ai.domain.ModelRoute;
import com.afrotransact.ai.domain.ModelRoutingPolicy;
import com.afrotransact.ai.domain.PriceTable;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.domain.guardrail.DefaultGroundingVerifier;
import com.afrotransact.ai.domain.guardrail.DefaultInjectionFilter;
import com.afrotransact.ai.domain.guardrail.DefaultPiiRedactor;
import com.afrotransact.ai.domain.guardrail.DefaultTopicPolicy;
import com.afrotransact.ai.domain.guardrail.GroundingVerifier;
import com.afrotransact.ai.domain.guardrail.InjectionFilter;
import com.afrotransact.ai.domain.guardrail.PiiRedactor;
import com.afrotransact.ai.domain.guardrail.TopicPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit wiring of the framework-free domain policies, the routing and price tables, and settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    private static final BigDecimal MICROS = BigDecimal.valueOf(1_000_000);

    @Bean
    PriceTable priceTable(AiProperties props) {
        Map<String, PriceTable.ModelPrice> prices = new LinkedHashMap<>();
        for (AiProperties.Price p : props.prices()) {
            if (p.model() == null || p.model().isBlank()) {
                throw new IllegalStateException("at.ai.prices entries need a model id");
            }
            prices.put(p.model().trim(), new PriceTable.ModelPrice(p.input(), p.output()));
        }
        return new PriceTable(prices, new PriceTable.ModelPrice(props.defaultPrice().input(),
                props.defaultPrice().output()));
    }

    @Bean
    ModelRoutingPolicy modelRoutingPolicy(AiProperties props, PriceTable prices) {
        List<ModelRoute> routes = new ArrayList<>();
        props.routes().forEach((task, r) -> routes.add(new ModelRoute(
                TaskType.valueOf(task.trim().toUpperCase(Locale.ROOT).replace('-', '_')), r.models(), r.timeout(),
                r.maxRetries(), r.backoff())));
        return new DefaultModelRoutingPolicy(routes, prices);
    }

    @Bean
    BudgetPolicy budgetPolicy() {
        return new DefaultBudgetPolicy();
    }

    @Bean
    PiiRedactor piiRedactor() {
        return new DefaultPiiRedactor();
    }

    @Bean
    InjectionFilter injectionFilter() {
        return new DefaultInjectionFilter();
    }

    @Bean
    TopicPolicy topicPolicy() {
        return new DefaultTopicPolicy();
    }

    @Bean
    GroundingVerifier groundingVerifier() {
        return new DefaultGroundingVerifier();
    }

    @Bean
    AiSettings aiSettings(AiProperties props) {
        Map<AiFeature, BudgetLimits> budgets = new EnumMap<>(AiFeature.class);
        props.budgets().forEach((feature, b) -> budgets.put(AiFeature.fromKey(feature), new BudgetLimits(
                b.userDailyTokens(), micros(b.userDailyCostUsd()), micros(b.featureDailyCostUsd()),
                b.degradeOnExceed(), b.hardLimitFactor())));
        AiProperties.Lola lola = props.lola();
        return new AiSettings(budgets, lola.maxToolRounds(), lola.memoryTurns(), lola.searchResults(),
                lola.maxRecipeItems(), props.maxInputChars(), lola.maxCompletionTokens(),
                props.embeddings().dimensions(), "deny".equals(props.providerPrivacy().dataCollection()),
                props.providerPrivacy().zeroDataRetention());
    }

    @Bean
    TransactionOperations aiTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** Lola turns run on virtual threads so a slow model never ties up a servlet thread. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService lolaExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private static long micros(BigDecimal usd) {
        return usd.multiply(MICROS).longValue();
    }
}
