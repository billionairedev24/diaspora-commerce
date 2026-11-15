package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.afrotransact.module.core.fixture.alpha.AlphaJob;
import com.afrotransact.module.core.fixture.shared.PlatformJob;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

/** A job runs on the pool of the module whose bean owns it, so one module's slow job cannot delay another's. */
class ModuleSchedulerTest {

    @Configuration
    @EnableScheduling
    static class Config {

        @Bean
        ModuleRegistry registry() {
            ModuleRegistry registry = new ModuleRegistry("core");
            registry.add(new ModuleDefinition("alpha", "alpha", "com.afrotransact.module.core.fixture.alpha", "x.A",
                    List.of()), new ModuleProperties(true, new ModuleProperties.Database("u", "u", "p", 4, 0,
                    Duration.ofSeconds(1), Duration.ofSeconds(1)), new ModuleProperties.Migration(true, null),
                    new ModuleProperties.Scheduler(2), new ModuleProperties.Events(true, 1, true), null, null));
            return registry;
        }

        @Bean(name = "taskScheduler")
        TaskScheduler taskScheduler(ModuleRegistry registry) {
            return new ModuleRoutingTaskScheduler(registry, 1);
        }

        @Bean
        AlphaJob alphaJob() {
            return new AlphaJob();
        }

        @Bean
        PlatformJob platformJob() {
            return new PlatformJob();
        }
    }

    @Test
    void eachJobRunsOnItsOwnersPool() throws InterruptedException {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Config.class)) {
            AlphaJob alpha = ctx.getBean(AlphaJob.class);
            PlatformJob platform = ctx.getBean(PlatformJob.class);
            for (int i = 0; i < 100 && (alpha.threads.isEmpty() || platform.threads.isEmpty()); i++) {
                Thread.sleep(50);
            }
            assertThat(alpha.threads).isNotEmpty().allMatch(n -> n.startsWith("sched-alpha-"));
            assertThat(platform.threads).isNotEmpty().allMatch(n -> n.startsWith("sched-core-"));
        }
    }
}
