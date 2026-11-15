package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.util.ClassUtils;

/**
 * Every Kafka listener of every module keeps the consumer group of its standalone service ({@code <module>-service[-suffix]}),
 * because core does not set {@code spring.kafka.consumer.group-id} and a listener's default is what applies. A listener that
 * resolves to the application name ({@code afrotransact-core}) would move its consumers to a new group and lose their offsets.
 */
class KafkaGroupIdsTest {

    private static final Pattern DEFAULT = Pattern.compile("\\$\\{[^:}]+:([^}]+)}(.*)");
    private static final Pattern STANDALONE_GROUP = Pattern.compile("[a-z]+(-[a-z]+)*-service(-[a-z-]+)?");

    @Test
    void everyListenerResolvesToItsModulesGroup() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> reader.getClassMetadata().getClassName().startsWith("com.afrotransact")
                && !reader.getClassMetadata().getClassName().startsWith("com.afrotransact.core"));
        List<String> groups = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.afrotransact")) {
            Class<?> type = ClassUtils.forName(definition.getBeanClassName(), getClass().getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                KafkaListener listener = AnnotatedElementUtils.findMergedAnnotation(method, KafkaListener.class);
                if (listener == null) {
                    continue;
                }
                String expression = listener.groupId();
                Matcher matcher = DEFAULT.matcher(expression);
                String resolved = matcher.matches() ? matcher.group(1) + matcher.group(2) : expression;
                groups.add(type.getSimpleName() + "." + method.getName() + " -> " + resolved);
                if (resolved.isBlank() || !STANDALONE_GROUP.matcher(resolved).matches()) {
                    problems.add(type.getName() + "." + method.getName() + " has group '" + expression + "' (resolves to '"
                            + resolved + "'); use ${spring.kafka.consumer.group-id:<module>-service}");
                }
            }
        }
        assertThat(groups).as("listeners found").hasSizeGreaterThan(30);
        assertThat(problems).isEmpty();
    }
}
