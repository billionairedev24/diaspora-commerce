package com.afrotransact.platform.security.relationship.openfga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.DefaultPermissionChecker;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.RelationshipAutoConfiguration;
import com.afrotransact.platform.security.relationship.RelationshipChecker;
import com.afrotransact.platform.security.relationship.RelationshipWriter;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenFgaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenFgaAutoConfiguration.class,
                    RelationshipAutoConfiguration.class))
            .withBean(PermissionChecker.class, () -> new DefaultPermissionChecker(() -> new Actor("u",
                    PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("*"))));

    @Test
    void disabledByDefaultLeavesOnlyTheRbacChecker() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(RelationshipChecker.class).doesNotHaveBean(RelationshipWriter.class);
            assertThat(ctx.getBean(ResourceAccessChecker.class).relationshipsEnabled()).isFalse();
        });
    }

    @Test
    void enabledWiresTheOpenFgaAdapters() {
        runner.withPropertyValues("at.security.fga.enabled=true", "at.security.fga.api-url=http://openfga:8080",
                "at.security.fga.store-id=S1", "at.security.fga.cache-ttl=2s", "at.security.fga.enforce=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RelationshipChecker.class).hasSingleBean(RelationshipWriter.class)
                            .hasSingleBean(ResourceAccessChecker.class);
                    assertThat(ctx.getBean(ResourceAccessChecker.class).relationshipsEnabled()).isTrue();
                    OpenFgaProperties props = ctx.getBean(OpenFgaProperties.class);
                    assertThat(props.enforce()).isFalse();
                    assertThat(props.storeName()).isEqualTo("afrotransact");
                    assertThat(props.cacheTtl()).hasSeconds(2);
                });
    }
}
