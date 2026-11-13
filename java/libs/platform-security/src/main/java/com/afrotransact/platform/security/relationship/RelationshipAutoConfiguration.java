package com.afrotransact.platform.security.relationship;

import com.afrotransact.platform.security.PermissionChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * RBAC-only {@link ResourceAccessChecker} for services without {@code at.security.fga.enabled=true}. Runs after
 * {@code OpenFgaAutoConfiguration}, whose relationship-aware checker wins when FGA is enabled.
 */
@AutoConfiguration(afterName = {"com.afrotransact.platform.security.PlatformSecurityAutoConfiguration",
    "com.afrotransact.platform.security.relationship.openfga.OpenFgaAutoConfiguration"})
public class RelationshipAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(RelationshipAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ResourceAccessChecker resourceAccessChecker(PermissionChecker permissions) {
        LOG.info("FGA disabled: isolation by RBAC + org claim");
        return ResourceAccessCheckers.rbacOnly(permissions);
    }
}
