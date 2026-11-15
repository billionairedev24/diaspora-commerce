package com.afrotransact.seller.adapter.out.module;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.ai.api.AiUsageApi;
import com.afrotransact.content.api.AdSpendApi;
import com.afrotransact.inventory.api.StorageUsageApi;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.seller.port.AdSpendSource;
import com.afrotransact.seller.port.AiUsageSource;
import com.afrotransact.seller.port.StorageUsageSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The three metered-billing sources through the callee modules' published APIs inside this JVM (mode {@code in-process}, per
 * edge {@code at.seller.inventory.mode}, {@code at.seller.content.mode}, {@code at.seller.aigateway.mode}): the same samples as
 * {@code GET /v1/storage/usage}, {@code GET /v1/ads/spend} and {@code GET /v1/ai/usage/orgs}, entered as seller's caller principal
 * (scopes {@code inventory:storage.read}, {@code ads:spend.read}, {@code ai:usage.read}). A refusal or failure is a
 * {@code seller.usage-source-*} gateway failure, as over HTTP.
 */
@Configuration(proxyBeanMethods = false)
class InProcessUsageSources {

    private static final String CODE = "seller.usage-source";

    @Bean
    @ConditionalOnModuleMode(caller = "seller", callee = "inventory", value = ModuleMode.IN_PROCESS)
    StorageUsageSource sellerInProcessStorageUsage(ObjectProvider<StorageUsageApi> api,
                                                   ObjectProvider<CallerPrincipals> callers, ListableBeanFactory beans) {
        SellerModuleCalls<StorageUsageApi> inventory = new SellerModuleCalls<>(StorageUsageApi.class, "inventory", api,
                callers, beans);
        return new StorageUsageSource() {
            @Override
            public List<StorageSample> cubicFeet(LocalDate from, LocalDate to) {
                return inventory.call(a -> a.usage(from, to), CODE).stream()
                        .map(d -> new StorageSample(d.ownerOrg(), d.day(), d.cubicFeet())).toList();
            }
        };
    }

    @Bean
    @ConditionalOnModuleMode(caller = "seller", callee = "content", value = ModuleMode.IN_PROCESS)
    AdSpendSource sellerInProcessAdSpend(ObjectProvider<AdSpendApi> api, ObjectProvider<CallerPrincipals> callers,
                                         ListableBeanFactory beans) {
        SellerModuleCalls<AdSpendApi> content = new SellerModuleCalls<>(AdSpendApi.class, "content", api, callers, beans);
        return new AdSpendSource() {
            @Override
            public List<AdSpendSample> spend(LocalDate from, LocalDate to) {
                return content.call(a -> a.spend(from, to), CODE).stream()
                        .map(i -> new AdSpendSample(i.sellerOrgId(), i.day(), i.currency(), i.spendMinor())).toList();
            }
        };
    }

    @Bean
    @ConditionalOnModuleMode(caller = "seller", callee = "ai-gateway", value = ModuleMode.IN_PROCESS)
    AiUsageSource sellerInProcessAiUsage(ObjectProvider<AiUsageApi> api, ObjectProvider<CallerPrincipals> callers,
                                         ListableBeanFactory beans) {
        SellerModuleCalls<AiUsageApi> ai = new SellerModuleCalls<>(AiUsageApi.class, "ai-gateway", api, callers, beans);
        return new AiUsageSource() {
            @Override
            public List<AiCreditSample> credits(LocalDate from, LocalDate to) {
                return ai.call(a -> a.usage(from, to), CODE).stream()
                        .map(i -> new AiCreditSample(i.orgId(), i.day(), i.credits())).toList();
            }
        };
    }
}
