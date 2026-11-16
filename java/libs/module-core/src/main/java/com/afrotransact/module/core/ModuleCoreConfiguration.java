package com.afrotransact.module.core;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.security.ActingAsActorResolver;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.JwtActorResolver;
import com.afrotransact.platform.security.LocalActorResolver;
import com.afrotransact.platform.security.PlatformSecurityProperties;
import io.micrometer.context.ThreadLocalAccessor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.condition.RequestCondition;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
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

    /**
     * The gate every in-process call goes through: the callee runs on its own module's lane with a bounded wait and a bulkhead
     * ({@link BoundedModuleCalls}). Installed into {@link InProcessCalls} here and removed when the context closes.
     */
    @Bean(destroyMethod = "close")
    BoundedModuleCalls moduleCallGate(ModuleRegistry modules, Environment environment, ObjectProvider<MeterRegistry> meters,
                                      ObjectProvider<ObservationRegistry> observations,
                                      ObjectProvider<ThreadLocalAccessor<?>> accessors) {
        observations.ifAvailable(registry -> ObservationThreadLocalAccessor.getInstance().setObservationRegistry(registry));
        BoundedModuleCalls gate = new BoundedModuleCalls(new ModuleCallSettings(environment, modules),
                meters.getIfAvailable(SimpleMeterRegistry::new), BoundedModuleCalls.snapshots(accessors.orderedStream().toList()));
        InProcessCalls.install(gate);
        return gate;
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

    /** Gives every handler method its module as a route condition so identical routes of two modules can coexist. */
    @Bean
    WebMvcRegistrations moduleWebMvcRegistrations(ModuleRegistry modules) {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new RequestMappingHandlerMapping() {
                    @Override
                    protected RequestCondition<?> getCustomMethodCondition(Method method) {
                        return modules.moduleOf(method.getDeclaringClass())
                                .map(m -> new ModuleRouteCondition(m.name(), modules.indexOf(m.name()))).orElse(null);
                    }
                };
            }
        };
    }

    @Bean
    FilterRegistrationBean<ModuleRouteFilter> moduleRouteFilter(ModuleRegistry modules, Environment environment) {
        Map<Integer, String> ports = Binder.get(environment).bind("at.core.module-ports",
                Bindable.mapOf(Integer.class, String.class)).orElse(Map.of());
        FilterRegistrationBean<ModuleRouteFilter> registration = new FilterRegistrationBean<>(
                new ModuleRouteFilter(modules, ports));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
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
