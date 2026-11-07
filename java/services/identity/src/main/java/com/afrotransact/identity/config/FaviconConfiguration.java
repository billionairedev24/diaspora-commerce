package com.afrotransact.identity.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Browsers still ask for {@code /favicon.ico}; answer with a redirect to the logo mark the pages already link. */
@Configuration(proxyBeanMethods = false)
class FaviconConfiguration implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/favicon.ico", "/assets/logo-mark.svg");
    }
}
