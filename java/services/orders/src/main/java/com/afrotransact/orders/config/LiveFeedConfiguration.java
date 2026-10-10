package com.afrotransact.orders.config;

import com.afrotransact.orders.adapter.out.feed.InProcessShipmentFeed;
import com.afrotransact.orders.adapter.out.valkey.ValkeyShipmentFeed;
import com.afrotransact.orders.port.LocalShipmentFeed;
import com.afrotransact.orders.port.ShipmentFeed;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live shipment feed fan-out (S219), {@code at.orders.live-feed.fanout}: {@code valkey} (default) publishes every
 * transition on a Valkey pub/sub channel that every replica subscribes to; {@code local} keeps it on this replica.
 */
@Configuration(proxyBeanMethods = false)
class LiveFeedConfiguration {

    private static final String FANOUT = "at.orders.live-feed";

    @Bean
    @ConditionalOnProperty(prefix = FANOUT, name = "fanout", havingValue = "valkey", matchIfMissing = true)
    ValkeyShipmentFeed valkeyShipmentFeed(StringRedisTemplate redis, LocalShipmentFeed local, JsonMapper json,
                                          @Value("${at.orders.live-feed.channel:at:orders:shipment-status}")
                                          String channel) {
        return new ValkeyShipmentFeed(redis, local, json, channel);
    }

    @Bean
    @ConditionalOnProperty(prefix = FANOUT, name = "fanout", havingValue = "valkey", matchIfMissing = true)
    RedisMessageListenerContainer shipmentFeedListener(RedisConnectionFactory connections, ValkeyShipmentFeed feed) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(feed, new ChannelTopic(feed.channel()));
        return container;
    }

    @Bean
    @ConditionalOnProperty(prefix = FANOUT, name = "fanout", havingValue = "local")
    ShipmentFeed inProcessShipmentFeed(LocalShipmentFeed local) {
        return new InProcessShipmentFeed(local);
    }
}
