package com.afrotransact.platform.events.consumer;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Serializer of the dead-letter producer. Listener records carry whatever the service's consumer deserializer produced
 * (normally {@code String}, after an {@code ErrorHandlingDeserializer} failure raw {@code byte[]}); both are written
 * unchanged so a DLQ record is byte-identical to the original.
 */
public class PassThroughSerializer implements Serializer<Object> {

    @Override
    public byte[] serialize(String topic, Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof byte[] bytes) {
            return bytes;
        }
        if (data instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        throw new SerializationException("Cannot dead-letter a " + data.getClass().getName()
                + " value: configure String or byte[] consumer deserializers");
    }
}
