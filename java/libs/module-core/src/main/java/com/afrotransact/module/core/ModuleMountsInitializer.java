package com.afrotransact.module.core;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Installs {@link ModuleMounts} as the first bean factory post-processor of an application that uses {@link EnableModules}. */
public class ModuleMountsInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ModuleMounts mounts = new ModuleMounts();
        mounts.setEnvironment(context.getEnvironment());
        mounts.setResourceLoader(context);
        mounts.setBeanClassLoader(context.getClassLoader());
        context.addBeanFactoryPostProcessor(mounts);
    }
}
