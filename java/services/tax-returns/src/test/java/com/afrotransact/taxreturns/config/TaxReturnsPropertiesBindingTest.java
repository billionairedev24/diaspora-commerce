package com.afrotransact.taxreturns.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import com.afrotransact.taxreturns.domain.profile.ApprovalPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The shipped application.yml binds into the properties records (nested records, enums, lists, durations, a ZoneId). */
class TaxReturnsPropertiesBindingTest {

    private static TaxReturnsProperties bind() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"));
        loaded.forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("at.taxreturns", TaxReturnsProperties.class).get();
    }

    @Test
    void theShippedDefaultsBind() throws IOException {
        TaxReturnsProperties p = bind();

        assertThat(p.tax().provider()).isEqualTo(TaxReturnsProperties.TaxProvider.RULES);
        assertThat(p.tax().classification().ruleStates()).containsExactly("TX");
        assertThat(p.tax().classification().approvalPolicy()).isEqualTo(ApprovalPolicy.ALL);
        assertThat(p.tax().classification().requireVerifiedCodes()).isFalse();
        assertThat(p.tax().classification().zone()).isEqualTo(ZoneId.of("America/Chicago"));
        assertThat(p.tax().shadow().timeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(p.tax().shadow().maxPending()).isEqualTo(50);
        assertThat(p.tax().shadow().cartQuotes()).isFalse();
        assertThat(p.tax().avalara().configured()).isFalse();
        assertThat(p.tax().avalara().baseUrl()).isEqualTo("https://sandbox-rest.avatax.com");
        assertThat(p.consumers().catalogTopic()).isEqualTo("at.product.events");
        assertThat(p.consumers().orderTopic()).isEqualTo("at.order.events");
    }
}
