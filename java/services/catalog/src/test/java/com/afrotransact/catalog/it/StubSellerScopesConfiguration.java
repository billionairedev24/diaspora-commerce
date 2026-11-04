package com.afrotransact.catalog.it;

import java.util.List;
import java.util.Set;

import com.afrotransact.catalog.port.SellerScopes;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The integration tests run without seller-service: every organisation is exempt from the department scope, so the
 * tests that are not about it keep listing wherever they like. The scope itself is covered by unit tests
 * ({@code DepartmentScopeTest}, {@code ScopedOperationsTest}, {@code SellerScopeGuardImplTest}). Registered for the test
 * classpath only, in {@code META-INF/spring/…AutoConfiguration.imports} under src/test/resources.
 */
@AutoConfiguration
public class StubSellerScopesConfiguration {

    @Bean
    @Primary
    SellerScopes everyOrganisationIsExempt() {
        return orgId -> new SellerScopes.OrgScope(true, Set.of(), List.of());
    }
}
