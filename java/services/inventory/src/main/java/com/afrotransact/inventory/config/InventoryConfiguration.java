package com.afrotransact.inventory.config;

import com.afrotransact.inventory.application.InventorySettings;
import com.afrotransact.inventory.domain.DefaultFefoAllocator;
import com.afrotransact.inventory.domain.DefaultReconciler;
import com.afrotransact.inventory.domain.DefaultShelfLifePolicy;
import com.afrotransact.inventory.domain.FefoAllocator;
import com.afrotransact.inventory.domain.Reconciler;
import com.afrotransact.inventory.domain.ShelfLifePolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit wiring of framework-free domain policies and settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryProperties.class)
class InventoryConfiguration {

    @Bean
    FefoAllocator fefoAllocator() {
        return new DefaultFefoAllocator();
    }

    @Bean
    ShelfLifePolicy shelfLifePolicy(InventoryProperties props) {
        return new DefaultShelfLifePolicy(props.shelfLife().minDays(), props.shelfLife().minFraction());
    }

    @Bean
    Reconciler reconciler() {
        return new DefaultReconciler();
    }

    @Bean
    InventorySettings inventorySettings(InventoryProperties props) {
        return new InventorySettings(props.retailOrgId(), props.reservationTtl(), props.fefoMinRemainingDays(),
                props.jobs().reservationSweepBatch(), props.storage().defaultUnitCubicFeet());
    }

    @Bean
    TransactionOperations inventoryTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
