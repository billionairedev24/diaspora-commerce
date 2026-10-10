package com.afrotransact.identity.config;

import java.util.Set;

import com.afrotransact.identity.adapter.out.crypto.DataKeyWrapper;
import com.afrotransact.identity.adapter.out.crypto.DataProtectionSettings;
import com.afrotransact.identity.application.DataProtectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Encryption at rest of MFA secrets ({@code at.identity.data-protection}). The {@code local} provider (default) builds a
 * key ring from {@code IDENTITY_DATA_KEY} / {@code IDENTITY_DATA_KEYS_PREVIOUS}; a cloud provider is selected by
 * {@code provider} and must contribute its own {@link DataKeyWrapper} bean. At start-up, legacy plaintext TOTP
 * secrets are sealed (idempotent, safe with several replicas starting together).
 */
@Configuration(proxyBeanMethods = false)
class DataProtectionConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DataProtectionConfiguration.class);
    private static final Set<String> PROVIDERS = Set.of("local", "aws-kms", "gcp-kms", "azure-key-vault");

    @Bean
    DataProtectionSettings dataProtectionSettings(IdentityProperties props) {
        IdentityProperties.DataProtection dp = props.dataProtection();
        if (!PROVIDERS.contains(dp.provider())) {
            throw new IllegalStateException("at.identity.data-protection.provider must be one of " + PROVIDERS
                    + ", got " + dp.provider());
        }
        return new DataProtectionSettings(dp.keyId(), dp.key(), dp.previousKeys());
    }

    @Bean
    ApplicationRunner dataProtectionMigrator(IdentityProperties props, ObjectProvider<DataKeyWrapper> wrappers,
                                             DataProtectionService dataProtection) {
        IdentityProperties.DataProtection dp = props.dataProtection();
        if (wrappers.getIfAvailable() == null) {
            throw new IllegalStateException("at.identity.data-protection.provider=" + dp.provider()
                    + " needs a DataKeyWrapper adapter for that KMS on the classpath (see README, Data protection)");
        }
        return (ApplicationArguments args) -> {
            if (dp.sealOnStartup()) {
                dataProtection.sealPlaintextSecrets();
            } else {
                LOG.info("Sealing legacy plaintext TOTP secrets at start-up is off (seal-on-startup=false)");
            }
        };
    }
}
