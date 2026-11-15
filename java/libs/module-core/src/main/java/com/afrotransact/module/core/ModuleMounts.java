package com.afrotransact.module.core;

import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

/**
 * Mounts the modules named in {@link EnableModules}: reads {@code at.modules.<name>} from the environment, scans each enabled
 * module's package, gives it its own infrastructure and installs the resolver that keeps modules apart. A disabled module is not
 * scanned, opens no connection and has no health indicator.
 */
final class ModuleMounts implements BeanDefinitionRegistryPostProcessor, PriorityOrdered, EnvironmentAware,
        ResourceLoaderAware, BeanClassLoaderAware {

    private static final Log LOG = LogFactory.getLog(ModuleMounts.class);

    private Environment environment;
    private ResourceLoader resourceLoader;
    private ClassLoader classLoader;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }

    /**
     * Runs before any configuration class is parsed (installed by {@link ModuleMountsInitializer}), so the modules' configuration
     * classes are registered ahead of Spring Boot's auto-configuration: a module's own bean (a JSON mapper, an authorization
     * server setting) is seen by {@code @ConditionalOnMissingBean} and is not shadowed by the auto-configured one.
     */
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (String name : registry.getBeanDefinitionNames()) {
            if (registry.getBeanDefinition(name) instanceof AnnotatedBeanDefinition annotated
                    && annotated.getMetadata().isAnnotated(EnableModules.class.getName())) {
                registerBeanDefinitions(annotated.getMetadata(), registry);
                return;
            }
        }
    }

    void registerBeanDefinitions(AnnotationMetadata importing, BeanDefinitionRegistry registry) {
        Map<String, Object> raw = importing.getAnnotationAttributes(EnableModules.class.getName());
        AnnotationAttributes[] specs = AnnotationAttributes.fromMap(raw).getAnnotationArray("value");
        ModuleRegistry modules = new ModuleRegistry(environment.getProperty("at.modules.core-service",
                "afrotransact-core"));
        Binder binder = Binder.get(environment);
        for (AnnotationAttributes spec : specs) {
            ModuleDefinition module = new ModuleDefinition(spec.getString("name"), spec.getString("service"),
                    spec.getString("basePackage"), spec.getString("application"),
                    List.of(spec.getStringArray("meterPrefixes")));
            ModuleProperties properties = binder.bindOrCreate("at.modules." + module.name(), ModuleProperties.class);
            if (!properties.enabled()) {
                LOG.info("Module '" + module.name() + "' is disabled (at.modules." + module.name() + ".enabled=false)");
                continue;
            }
            if (!ClassUtils.isPresent(module.application(), classLoader)) {
                LOG.warn("Module '" + module.name() + "' is enabled but is not part of this build (" + module.application()
                        + " is not on the classpath; built without it, e.g. -DnoAi): not mounted");
                continue;
            }
            modules.add(module, properties);
            scan(module, registry);
            new ModuleInfrastructure(module, properties, environment, registry, classLoader).register();
            LOG.info("Module '" + module.name() + "' mounted from " + module.basePackage());
        }
        registry.registerBeanDefinition("moduleRegistry",
                BeanDefinitionBuilder.genericBeanDefinition(ModuleRegistry.class, () -> modules).getBeanDefinition());
        registerRouter(modules, registry);
        if (registry instanceof DefaultListableBeanFactory factory) {
            factory.setAutowireCandidateResolver(new ModuleAwareAutowireCandidateResolver(modules));
        } else {
            throw new IllegalStateException("Modules need a DefaultListableBeanFactory, got " + registry.getClass());
        }
    }

    private void scan(ModuleDefinition module, BeanDefinitionRegistry registry) {
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(registry, true, environment);
        scanner.setResourceLoader(resourceLoader);
        scanner.setBeanNameGenerator(new ModuleBeanNameGenerator(module.name()));
        scanner.addExcludeFilter((reader, factory) -> reader.getClassMetadata().getClassName()
                .equals(module.application()));
        scanner.scan(module.basePackage());
    }

    private void registerRouter(ModuleRegistry modules, BeanDefinitionRegistry registry) {
        if (modules.modules().isEmpty() || !(registry instanceof DefaultListableBeanFactory factory)) {
            return;
        }
        AbstractBeanDefinition router = BeanDefinitionBuilder.genericBeanDefinition(PlatformTransactionManager.class,
                () -> new ModuleRoutingTransactionManager(modules, factory)).getBeanDefinition();
        router.setPrimary(true);
        router.setAttribute(ModuleAttributes.MODULE, ModuleAttributes.ROUTER);
        registry.registerBeanDefinition("transactionManager", router);
    }
}
