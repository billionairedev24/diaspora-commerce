package com.afrotransact.module.core;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.type.MethodMetadata;

/**
 * Makes by-type injection work with one DataSource, JdbcClient, transaction manager, outbox store ... per module.
 *
 * <p>A bean that belongs to a module (attribute {@code at.module}) is a candidate only for classes of the same module. A class
 * outside every module (framework and platform code) sees all of them, as it would see any bean, so collections and actuator
 * work. The routing transaction manager is the reverse: modules never get it (they get their own), framework code does. The
 * rule is what lets a module keep injecting a plain {@code JdbcClient} and never see another module's database.
 */
final class ModuleAwareAutowireCandidateResolver extends ContextAnnotationAutowireCandidateResolver {

    private final ModuleRegistry modules;

    ModuleAwareAutowireCandidateResolver(ModuleRegistry modules) {
        this.modules = modules;
    }

    @Override
    public boolean isAutowireCandidate(BeanDefinitionHolder holder, DependencyDescriptor descriptor) {
        if (!super.isAutowireCandidate(holder, descriptor)) {
            return false;
        }
        Object owner = ownerOf(holder.getBeanDefinition());
        if (owner == null) {
            return true;
        }
        Class<?> consumer = consumerOf(descriptor);
        String consumerModule = consumer == null ? null : modules.moduleOf(consumer).map(ModuleDefinition::name)
                .orElse(null);
        if (ModuleAttributes.ROUTER.equals(owner)) {
            return consumerModule == null;
        }
        return consumerModule == null || consumerModule.equals(owner);
    }

    /**
     * The module a bean belongs to: the infrastructure attribute, or, for a module's own {@code @Bean} method that returns a
     * platform or framework type (a {@code TransactionTemplate}, an {@code ObjectMapper}), the module that declares the method.
     * Beans of the module's own types stay shared on purpose: they are the module's published API adapters.
     */
    private Object ownerOf(BeanDefinition definition) {
        Object attribute = definition.getAttribute(ModuleAttributes.MODULE);
        if (attribute != null) {
            return attribute;
        }
        if (definition instanceof AnnotatedBeanDefinition annotated && annotated.getFactoryMethodMetadata() != null) {
            MethodMetadata method = annotated.getFactoryMethodMetadata();
            if (modules.moduleOfClassName(method.getReturnTypeName()).isEmpty()) {
                return modules.moduleOfClassName(method.getDeclaringClassName()).map(ModuleDefinition::name)
                        .orElse(null);
            }
        }
        return null;
    }

    private static Class<?> consumerOf(DependencyDescriptor descriptor) {
        if (descriptor.getField() != null) {
            return descriptor.getField().getDeclaringClass();
        }
        return descriptor.getMethodParameter() == null ? null : descriptor.getMethodParameter().getDeclaringClass();
    }
}
