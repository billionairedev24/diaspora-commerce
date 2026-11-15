package com.afrotransact.module.core;

import java.util.Locale;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.platform.security.ActingAsActorResolver;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.JwtActorResolver;
import com.afrotransact.platform.security.LocalActorResolver;
import com.afrotransact.platform.security.PlatformSecurityProperties;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationConvention;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The beans the whole application shares, as opposed to the per-module ones {@link ModuleInfrastructure} registers. */
@Configuration(proxyBeanMethods = false)
public class ModuleCoreConfiguration {

    /** The name used in health group and bean names: lower-case letters and digits of the module name. */
    static String healthKey(ModuleDefinition module) {
        return module.name().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /** The token's actor, or inside an in-process module call the calling module (see {@link CallerAwareActorResolver}). */
    @Bean
    ActorResolver actorResolver(PlatformSecurityProperties props) {
        return new CallerAwareActorResolver(new ActingAsActorResolver(
                props.enabled() ? new JwtActorResolver() : new LocalActorResolver()));
    }

    @Bean
    CallerPrincipals callerPrincipals(ModuleRegistry modules) {
        return new ModuleCallerRegistry(modules);
    }

    /** One scheduler pool per module; see {@link ModuleRoutingTaskScheduler}. The bean name makes Boot's own scheduler back off. */
    @Bean(name = "taskScheduler")
    TaskScheduler taskScheduler(ModuleRegistry modules, @Value("${at.modules.core-scheduler-pool-size:1}") int corePool) {
        return new ModuleRoutingTaskScheduler(modules, corePool);
    }

    @Bean
    MeterFilter moduleMeterFilter(ModuleRegistry modules) {
        return new ModuleMeterFilter(modules);
    }

    @Bean
    ServerRequestObservationConvention moduleServerRequestObservationConvention(ModuleRegistry modules) {
        return new ModuleServerRequestObservationConvention(modules);
    }

    @Bean
    WebMvcConfigurer moduleMdcConfigurer(ModuleRegistry modules) {
        ModuleMdcInterceptor interceptor = new ModuleMdcInterceptor(modules);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
