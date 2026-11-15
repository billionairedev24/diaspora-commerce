package com.afrotransact.module.core;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;

/**
 * Names a module's scanned beans as the standalone service does ({@code productWriterImpl}), so that the module's
 * {@code @Qualifier("productWriterImpl")} references keep working. When two modules have a bean of the same name (for example
 * both have a {@code ClientCredentialsTokens}), the later module's bean is prefixed with its module name instead of the start
 * failing on an override: injection by type is unaffected; only a by-name reference to the later module's bean would need the
 * prefix, and it fails loudly at start.
 */
final class ModuleBeanNameGenerator implements BeanNameGenerator {

    private final String module;
    private final AnnotationBeanNameGenerator standard = AnnotationBeanNameGenerator.INSTANCE;

    ModuleBeanNameGenerator(String module) {
        this.module = module;
    }

    @Override
    public String generateBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
        String name = standard.generateBeanName(definition, registry);
        return registry.containsBeanDefinition(name) ? module + "." + name : name;
    }
}
