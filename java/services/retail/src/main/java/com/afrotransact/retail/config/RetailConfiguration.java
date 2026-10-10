package com.afrotransact.retail.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import com.afrotransact.retail.adapter.out.auth.ClientCredentialsTokenProvider;
import com.afrotransact.retail.adapter.out.catalog.HttpCatalogClient;
import com.afrotransact.retail.adapter.out.edi.X12Edi850Writer;
import com.afrotransact.retail.adapter.out.fx.ConfiguredFxRates;
import com.afrotransact.retail.adapter.out.inventory.HttpInventoryClient;
import com.afrotransact.retail.application.RetailSettings;
import com.afrotransact.retail.domain.ApprovalPolicy;
import com.afrotransact.retail.domain.CostAllocator;
import com.afrotransact.retail.domain.DefaultApprovalPolicy;
import com.afrotransact.retail.domain.DefaultCostAllocator;
import com.afrotransact.retail.domain.DefaultGlPostingRules;
import com.afrotransact.retail.domain.DefaultLandedCostCalculator;
import com.afrotransact.retail.domain.DefaultReceiptValidator;
import com.afrotransact.retail.domain.DefaultThreeWayMatcher;
import com.afrotransact.retail.domain.GlPostingRules;
import com.afrotransact.retail.domain.LandedCostCalculator;
import com.afrotransact.retail.domain.MatchTolerance;
import com.afrotransact.retail.domain.ReceiptValidator;
import com.afrotransact.retail.domain.ThreeWayMatcher;
import com.afrotransact.retail.port.CatalogClient;
import com.afrotransact.retail.port.FxRateProvider;
import com.afrotransact.retail.port.InventoryClient;
import com.afrotransact.retail.port.PurchaseOrderDocuments;
import com.afrotransact.retail.port.ServiceTokenProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Explicit wiring of framework-free domain policies, settings and outbound adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetailProperties.class)
class RetailConfiguration {

    @Bean
    RetailSettings retailSettings(RetailProperties props) {
        return new RetailSettings(props.baseCurrency(), props.gs1CompanyPrefix(), props.retailOwnerOrg(),
                new MatchTolerance(props.match().priceTolerancePercent()));
    }

    @Bean
    ApprovalPolicy approvalPolicy(RetailProperties props) {
        return new DefaultApprovalPolicy(props.approval().financeThresholdMinor());
    }

    @Bean
    CostAllocator costAllocator() {
        return new DefaultCostAllocator();
    }

    @Bean
    LandedCostCalculator landedCostCalculator(CostAllocator allocator) {
        return new DefaultLandedCostCalculator(allocator);
    }

    @Bean
    ReceiptValidator receiptValidator(RetailProperties props) {
        return new DefaultReceiptValidator(props.receiving().minRemainingDays(),
                props.receiving().minRemainingFraction(), props.receiving().overTolerancePercent());
    }

    @Bean
    ThreeWayMatcher threeWayMatcher() {
        return new DefaultThreeWayMatcher();
    }

    @Bean
    GlPostingRules glPostingRules() {
        return new DefaultGlPostingRules();
    }

    @Bean
    TransactionOperations retailTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    FxRateProvider fxRateProvider(RetailProperties props) {
        return new ConfiguredFxRates(props.fxRates());
    }

    @Bean
    PurchaseOrderDocuments purchaseOrderDocuments(Clock clock) {
        return new X12Edi850Writer("AFROTRANSACT", clock);
    }

    @Bean
    ServiceTokenProvider serviceTokenProvider(RestClient.Builder builder, RetailProperties props, JsonMapper json,
                                              Clock clock) {
        RetailProperties.Auth auth = props.auth();
        return new ClientCredentialsTokenProvider(builder.clone().requestFactory(factory(Duration.ofSeconds(10)))
                .build(), auth.tokenUri(), auth.clientId(), auth.clientSecret(), auth.scopes(), json, clock);
    }

    @Bean
    CatalogClient catalogClient(RestClient.Builder builder, RetailProperties props, JsonMapper json) {
        return new HttpCatalogClient(builder.clone().baseUrl(props.catalog().baseUrl())
                .requestFactory(factory(props.catalog().timeout())).build(), json);
    }

    @Bean
    InventoryClient inventoryClient(RestClient.Builder builder, RetailProperties props, ServiceTokenProvider tokens,
                                    JsonMapper json) {
        return new HttpInventoryClient(builder.clone().baseUrl(props.inventory().baseUrl())
                .requestFactory(factory(props.inventory().timeout())).build(), tokens, json);
    }

    private static JdkClientHttpRequestFactory factory(Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
