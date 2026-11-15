package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** The module's own settings are loaded; the process-wide ones are not, so two modules cannot overwrite each other. */
class ModuleConfigPostProcessorTest {

    private MockEnvironment load(String... properties) {
        return loadWith("local", properties);
    }

    private MockEnvironment loadWith(String profile, String... properties) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        for (String p : properties) {
            env.setProperty(p.substring(0, p.indexOf('=')), p.substring(p.indexOf('=') + 1));
        }
        new ModuleConfigPostProcessor().postProcessEnvironment(env, new SpringApplication());
        return env;
    }

    @Test
    void keepsTheModulesOwnSettingsAndTheProfileOverlay() {
        MockEnvironment env = load();
        assertThat(env.getProperty("at.alpha.limit")).isEqualTo("9");
        assertThat(env.getProperty("at.alpha.mode")).isEqualTo("http");
    }

    @Test
    void ignoresProcessWideSettingsOfTheModule() {
        MockEnvironment env = load();
        assertThat(env.getProperty("spring.datasource.url")).isNull();
        assertThat(env.getProperty("spring.kafka.consumer.group-id")).isNull();
        assertThat(env.getProperty("server.port")).isNull();
        assertThat(env.getProperty("spring.application.name")).isNull();
        assertThat(env.getProperty("management.metrics.tags.service")).isNull();
        assertThat(env.getProperty("at.security.enabled")).isNull();
        assertThat(env.getProperty("at.events.relay-enabled")).isNull();
        assertThat(env.getProperty("at.consumers.enabled")).isNull();
    }

    @Test
    void anonymousPathsComeFromTheModuleAndAProfileListReplacesTheBaseList() {
        MockEnvironment base = loadWith("prod");
        assertThat(base.getProperty("at.security.public-paths[0]")).isEqualTo("/v1/alpha/open");
        assertThat(base.getProperty("at.security.public-paths[1]")).isEqualTo("/v1/shared/**");
        MockEnvironment local = load();
        assertThat(local.getProperty("at.security.public-paths[0]")).isEqualTo("/v1/alpha/local-only");
        assertThat(local.getProperty("at.security.public-paths[1]")).isNull();
    }

    @Test
    void theApplicationsOwnSettingsWin() {
        MockEnvironment env = load("at.alpha.limit=1");
        assertThat(env.getProperty("at.alpha.limit")).isEqualTo("1");
    }

    @Test
    void aDisabledModuleLoadsNothing() {
        MockEnvironment env = load("at.modules.alpha.enabled=false");
        assertThat(env.getProperty("at.alpha.limit")).isNull();
        assertThat(env.getProperty("at.security.public-paths[0]")).isNull();
    }
}
