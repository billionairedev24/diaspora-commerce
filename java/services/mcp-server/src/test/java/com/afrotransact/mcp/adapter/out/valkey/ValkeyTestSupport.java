package com.afrotransact.mcp.adapter.out.valkey;

import java.time.Duration;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared Valkey Testcontainer (local image) and one-connection-per-"pod" templates. */
final class ValkeyTestSupport {

    static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse("valkey/valkey:8.1-alpine")).withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    private ValkeyTestSupport() {
    }

    /** A template with its own connection factory, as a separate replica would have. */
    static StringRedisTemplate template() {
        return template(VALKEY.getHost(), VALKEY.getMappedPort(6379));
    }

    static StringRedisTemplate template(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
