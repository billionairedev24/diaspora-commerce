package com.afrotransact.platform.events.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class EventRetentionPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(EventRetentionProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsAreNinetyDaysInboxAndFourteenDaysPublishedOutbox() {
        runner.run(ctx -> {
            EventRetentionProperties p = ctx.getBean(EventRetentionProperties.class);
            assertThat(p.enabled()).isTrue();
            assertThat(p.inboxRetention()).isEqualTo(Duration.ofDays(90));
            assertThat(p.outboxRetention()).isEqualTo(Duration.ofDays(14));
            assertThat(p.batchSize()).isEqualTo(5000);
            assertThat(p.maxBatches()).isEqualTo(200);
            assertThat(p.cron()).isEqualTo("0 30 3 * * *");
        });
    }

    @Test
    void thirtyFiveDaysIsAccepted() {
        runner.withPropertyValues("at.events.retention.inbox-retention=35d")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void inboxRetentionBelowThirtyFiveDaysRefusesToStart() {
        runner.withPropertyValues("at.events.retention.inbox-retention=34d").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("35 day minimum");
        });
    }

    @Test
    void inboxRetentionBelowTheMinimumRefusesToStartEvenWhenTheJobIsDisabled() {
        runner.withPropertyValues("at.events.retention.enabled=false", "at.events.retention.inbox-retention=7d")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void outboxRetentionBelowSevenDaysAndNonPositiveBatchSettingsAreRefused() {
        assertThatThrownBy(() -> new EventRetentionProperties(true, "0 0 3 * * *", Duration.ofDays(90),
                Duration.ofDays(6), 100, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EventRetentionProperties(true, "0 0 3 * * *", Duration.ofDays(90),
                Duration.ofDays(14), 0, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EventRetentionProperties(true, "0 0 3 * * *", Duration.ofDays(90),
                Duration.ofDays(14), 100, 0)).isInstanceOf(IllegalStateException.class);
    }
}
