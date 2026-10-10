package com.afrotransact.platform.security.relationship.openfga;

import java.net.http.HttpClient;

import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.FgaSubjectResolver;
import com.afrotransact.platform.security.relationship.RelationshipChecker;
import com.afrotransact.platform.security.relationship.RelationshipWriter;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessCheckers;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * OpenFGA relationship checks, opt-in with {@code at.security.fga.enabled=true}. Provides
 * {@link RelationshipChecker} (cached), {@link RelationshipWriter}, {@link FgaSubjectResolver} and the
 * relationship-aware {@link ResourceAccessChecker}. {@code PermissionChecker} is not touched.
 */
@AutoConfiguration(afterName = "com.afrotransact.platform.security.PlatformSecurityAutoConfiguration")
@ConditionalOnProperty(prefix = "at.security.fga", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OpenFgaProperties.class)
public class OpenFgaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenFgaClient openFgaClient(OpenFgaProperties props) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.timeout());
        RestClient.Builder builder = RestClient.builder().baseUrl(props.apiUrl().toString()).requestFactory(factory);
        if (props.apiToken() != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken());
        }
        return new OpenFgaClient(builder.build(), props);
    }

    @Bean
    @ConditionalOnMissingBean
    RelationshipCheckCache relationshipCheckCache(OpenFgaProperties props) {
        return new RelationshipCheckCache(props.cacheTtl(), props.cacheMaxEntries(), System::nanoTime);
    }

    @Bean
    @ConditionalOnMissingBean
    public RelationshipChecker relationshipChecker(OpenFgaClient client, RelationshipCheckCache cache) {
        return new OpenFgaRelationshipChecker(client, cache);
    }

    @Bean
    @ConditionalOnMissingBean
    public RelationshipWriter relationshipWriter(OpenFgaClient client, RelationshipCheckCache cache) {
        return new OpenFgaRelationshipWriter(client, cache);
    }

    @Bean
    @ConditionalOnMissingBean
    public FgaSubjectResolver fgaSubjectResolver() {
        return FgaSubjectResolver.standard();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResourceAccessChecker resourceAccessChecker(PermissionChecker permissions, RelationshipChecker relationships,
                                                       FgaSubjectResolver subjects, OpenFgaProperties props) {
        return ResourceAccessCheckers.withRelationships(permissions, relationships, subjects, props.enforce());
    }
}
