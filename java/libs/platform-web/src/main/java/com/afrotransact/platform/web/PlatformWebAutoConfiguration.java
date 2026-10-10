package com.afrotransact.platform.web;

import java.util.List;

import com.afrotransact.platform.web.docs.ApiDocsProperties;
import com.afrotransact.platform.web.docs.ApiReferenceController;
import com.afrotransact.platform.web.docs.ApiReferencePage;
import com.afrotransact.platform.web.docs.RedocApiReferencePage;
import com.afrotransact.platform.web.docs.ScalarApiReferencePage;
import com.afrotransact.platform.web.error.ProblemDetailsAdvice;
import com.afrotransact.platform.web.trace.CorrelationIdFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Wires the shared web conventions into every service that depends on platform-web. */
@AutoConfiguration
@EnableConfigurationProperties(ApiDocsProperties.class)
public class PlatformWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailsAdvice problemDetailsAdvice() {
        return new ProblemDetailsAdvice();
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>(new CorrelationIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI platformOpenApi(ApiDocsProperties props) {
        return new OpenAPI()
                .info(new Info()
                        .title(props.title())
                        .version(props.version())
                        .description(props.description())
                        .contact(new Contact().name("AfroTransact Platform").url(props.contactUrl())))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    @Bean
    @ConditionalOnProperty(prefix = "at.api-docs", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ApiReferencePage scalarApiReferencePage(ApiDocsProperties props) {
        return new ScalarApiReferencePage(props.scalarVersion());
    }

    @Bean
    @ConditionalOnProperty(prefix = "at.api-docs", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ApiReferencePage redocApiReferencePage(ApiDocsProperties props) {
        return new RedocApiReferencePage(props.redocVersion());
    }

    @Bean
    @ConditionalOnProperty(prefix = "at.api-docs", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ApiReferenceController apiReferenceController(List<ApiReferencePage> pages, ApiDocsProperties props) {
        return new ApiReferenceController(pages, props);
    }
}
