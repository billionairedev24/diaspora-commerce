package com.afrotransact.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.afrotransact.ai.application.AiSettings;
import com.afrotransact.ai.domain.SpendCapSettings;
import com.afrotransact.ai.domain.SpendLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AiPropertiesBindingTest {

    @Test
    void defaultsDenyDataCollectionWithoutZdr() {
        AiProperties props = bind(Map.of());
        assertThat(props.providerPrivacy().dataCollection()).isEqualTo("deny");
        assertThat(props.providerPrivacy().zeroDataRetention()).isFalse();
    }

    @Test
    void bindsKebabCaseKeys() {
        AiProperties props = bind(Map.of(
                "at.ai.provider-privacy.data-collection", "ALLOW",
                "at.ai.provider-privacy.zero-data-retention", "true"));
        assertThat(props.providerPrivacy().dataCollection()).isEqualTo("allow");
        assertThat(props.providerPrivacy().zeroDataRetention()).isTrue();
    }

    @Test
    void rejectsUnknownDataCollectionValues() {
        assertThatThrownBy(() -> bind(Map.of("at.ai.provider-privacy.data-collection", "maybe")))
                .hasStackTraceContaining("deny or allow");
    }

    @Test
    void settingsCarryThePrivacyPreferences() {
        AiConfiguration configuration = new AiConfiguration();
        AiSettings strict = configuration.aiSettings(bind(Map.of("at.ai.provider-privacy.zero-data-retention", "true")));
        assertThat(strict.denyDataCollection()).isTrue();
        assertThat(strict.zeroDataRetention()).isTrue();
        AiSettings open = configuration.aiSettings(bind(Map.of("at.ai.provider-privacy.data-collection", "allow")));
        assertThat(open.denyDataCollection()).isFalse();
        assertThat(open.zeroDataRetention()).isFalse();
    }

    @Test
    void spendCeilingDefaultsToTwoDollarsADay() {
        AiProperties.Spend spend = bind(Map.of()).spend();
        assertThat(spend.globalDailyCostUsd()).isEqualByComparingTo("2.00");
        assertThat(spend.globalDailyCostMaxUsd()).isEqualByComparingTo("20.00");
        assertThat(spend.capCacheSeconds()).isEqualTo(10);
        assertThat(spend.callerDailyCostUsd()).isEqualByComparingTo("0.50");
        assertThat(spend.userCallsPerMinute()).isEqualTo(20);
        assertThat(spend.serviceCallsPerMinute()).isEqualTo(120);
    }

    @Test
    void spendLimitsBecomeMicroDollars() {
        SpendLimits limits = new AiConfiguration().spendLimits(bind(Map.of()));
        assertThat(limits.callerDailyCostMicros()).isEqualTo(500_000);
    }

    @Test
    void spendCapSettingsAreTheInitialDefaultAndTheHardCeiling() {
        SpendCapSettings settings = new AiConfiguration().spendCapSettings(bind(Map.of()));
        assertThat(settings.defaultMicros()).isEqualTo(2_000_000);
        assertThat(settings.ceilingMicros()).isEqualTo(20_000_000);
        assertThat(settings.cacheTtl()).hasSeconds(10);

        SpendCapSettings custom = new AiConfiguration().spendCapSettings(bind(Map.of(
                "at.ai.spend.global-daily-cost-usd", "5", "at.ai.spend.global-daily-cost-max-usd", "8")));
        assertThat(custom.defaultMicros()).isEqualTo(5_000_000);
        assertThat(custom.ceilingMicros()).isEqualTo(8_000_000);
    }

    @Test
    void aDefaultAboveTheCeilingStopsTheServiceFromStarting() {
        Map<String, String> source = Map.of("at.ai.spend.global-daily-cost-usd", "25");
        assertThatThrownBy(() -> new AiConfiguration().spendCapSettings(bind(source)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AI_GLOBAL_DAILY_COST_MAX_USD");
    }

    private static AiProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("at.ai", Bindable.of(AiProperties.class))
                .orElseGet(() -> new AiProperties(null, null, null, null, null, null, null, null, null));
    }
}
