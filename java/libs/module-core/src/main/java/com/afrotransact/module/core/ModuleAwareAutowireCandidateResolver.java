package com.afrotransact.module.core;

import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;

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
        Object owner = holder.getBeanDefinition().getAttribute(ModuleAttributes.MODULE);
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

    private static Class<?> consumerOf(DependencyDescriptor descriptor) {
        if (descriptor.getField() != null) {
            return descriptor.getField().getDeclaringClass();
        }
        return descriptor.getMethodParameter() == null ? null : descriptor.getMethodParameter().getDeclaringClass();
    }
}
