package com.afrotransact.orders.adapter.out.valkey;

import java.nio.charset.StandardCharsets;

import com.afrotransact.orders.port.LocalShipmentFeed;
import com.afrotransact.orders.port.ShipmentFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cluster-wide live feed (S219): every replica publishes the transitions it committed to one Valkey pub/sub channel
 * and every replica (the publisher included) delivers what it receives to its own SSE subscribers, so staff and
 * sellers see every transition whichever replica they are connected to. When Valkey is unreachable the update is
 * still delivered on this replica (best effort; clients re-read on reconnect).
 */
public class ValkeyShipmentFeed implements ShipmentFeed, MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(ValkeyShipmentFeed.class);

    private final StringRedisTemplate redis;
    private final LocalShipmentFeed local;
    private final JsonMapper json;
    private final String channel;

    public ValkeyShipmentFeed(StringRedisTemplate redis, LocalShipmentFeed local, JsonMapper json, String channel) {
        this.redis = redis;
        this.local = local;
        this.json = json;
        this.channel = channel;
    }

    public String channel() {
        return channel;
    }

    @Override
    public void publish(Update update) {
        String body;
        try {
            body = json.writeValueAsString(update);
        } catch (JacksonException e) {
            LOG.warn("Live feed update for shipment {} not serialisable; delivered locally only", update.shipmentId(),
                    e);
            local.deliver(update);
            return;
        }
        try {
            redis.convertAndSend(channel, body);
        } catch (RuntimeException e) {
            LOG.warn("Valkey unavailable for the live feed ({}); shipment {} delivered on this replica only",
                    e.getMessage(), update.shipmentId());
            local.deliver(update);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            local.deliver(json.readValue(body, Update.class));
        } catch (JacksonException | IllegalArgumentException e) {
            LOG.warn("Skipping malformed live feed message: {}", e.getMessage());
        }
    }
}
