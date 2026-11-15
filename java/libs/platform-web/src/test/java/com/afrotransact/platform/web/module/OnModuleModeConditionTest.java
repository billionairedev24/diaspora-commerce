package com.afrotransact.platform.web.module;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.module.ModuleMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Which adapter an edge selects: the edge's own mode, else the caller's, else http; a default gives way to a callee that is off. */
class OnModuleModeConditionTest {

    private static ModuleMode mode(MockEnvironment env) {
        return OnModuleModeCondition.effective(env, "catalog", "tax-returns");
    }

    @Test
    void standaloneIsHttpUntilToldOtherwise() {
        assertThat(mode(new MockEnvironment())).isEqualTo(ModuleMode.HTTP);
    }

    @Test
    void theCallerLevelModeAppliesToEveryEdgeAndTheEdgeModeOverridesIt() {
        MockEnvironment env = new MockEnvironment().withProperty("at.catalog.mode", "in-process");
        assertThat(mode(env)).isEqualTo(ModuleMode.IN_PROCESS);
        assertThat(mode(env.withProperty("at.catalog.taxreturns.mode", "http"))).isEqualTo(ModuleMode.HTTP);
        MockEnvironment other = new MockEnvironment().withProperty("at.catalog.mode", "http")
                .withProperty("at.catalog.taxreturns.mode", "in-process");
        assertThat(mode(other)).isEqualTo(ModuleMode.IN_PROCESS);
    }

    @Test
    void aDefaultGivesWayToACalleeThatIsSwitchedOffButAnExplicitEdgeDoesNot() {
        MockEnvironment off = new MockEnvironment().withProperty("at.catalog.mode", "in-process")
                .withProperty("at.modules.tax-returns.enabled", "false");
        assertThat(mode(off)).isEqualTo(ModuleMode.HTTP);
        assertThat(mode(off.withProperty("at.catalog.taxreturns.mode", "in-process"))).isEqualTo(ModuleMode.IN_PROCESS);
        MockEnvironment on = new MockEnvironment().withProperty("at.catalog.mode", "in-process")
                .withProperty("at.modules.tax-returns.enabled", "true");
        assertThat(mode(on)).isEqualTo(ModuleMode.IN_PROCESS);
    }

    @Test
    void dashesAreLeftOutOfThePropertyNames() {
        MockEnvironment env = new MockEnvironment().withProperty("at.taxreturns.aigateway.mode", "in-process");
        assertThat(OnModuleModeCondition.effective(env, "tax-returns", "ai-gateway")).isEqualTo(ModuleMode.IN_PROCESS);
    }
}
