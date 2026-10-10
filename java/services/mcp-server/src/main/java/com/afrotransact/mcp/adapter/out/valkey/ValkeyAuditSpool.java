package com.afrotransact.mcp.adapter.out.valkey;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.afrotransact.mcp.port.AuditSpool;
import com.afrotransact.mcp.port.AuditTrail;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit spool as a Valkey stream ({@code XADD} / {@code XRANGE} / {@code XDEL}). Stream ids are monotonic, so replay
 * order is arrival order across all replicas; a lease key ({@code SET NX PX}) lets one replica drain at a time.
 * The stream is capped (approximate {@code MAXLEN}) so a very long broker outage cannot exhaust Valkey memory; the
 * audit log line remains the backstop for anything trimmed.
 */
public final class ValkeyAuditSpool implements AuditSpool {

    private static final String FIELD = "event";
    private static final RedisScript<Long> RELEASE = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String stream;
    private final String leaseKey;
    private final long maxLength;

    /**
     * @param redis     Valkey connection
     * @param json      serialises events
     * @param stream    stream key, e.g. {@code mcp:audit:spool}
     * @param maxLength approximate cap of the stream
     */
    public ValkeyAuditSpool(StringRedisTemplate redis, JsonMapper json, String stream, long maxLength) {
        this.redis = redis;
        this.json = json;
        this.stream = stream;
        this.leaseKey = stream + ":replay-lease";
        this.maxLength = maxLength;
    }

    @Override
    public void append(AuditTrail.ToolInvoked event) {
        MapRecord<String, String, String> rec = StreamRecords.newRecord().in(stream)
                .ofMap(Map.of(FIELD, json.writeValueAsString(event)));
        redis.opsForStream().add(rec, RedisStreamCommands.XAddOptions.maxlen(maxLength).approximateTrimming(true));
    }

    @Override
    public List<Spooled> oldest(int limit) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .range(stream, Range.unbounded(), Limit.limit().count(limit));
        if (records == null) {
            return List.of();
        }
        return records.stream().map(r -> new Spooled(r.getId().getValue(),
                json.readValue(String.valueOf(r.getValue().get(FIELD)), AuditTrail.ToolInvoked.class))).toList();
    }

    @Override
    public void remove(String spoolId) {
        redis.opsForStream().delete(stream, spoolId);
    }

    @Override
    public long size() {
        Long size = redis.opsForStream().size(stream);
        return size == null ? 0 : size;
    }

    @Override
    public boolean tryLease(String owner, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(leaseKey, owner, ttl));
    }

    @Override
    public void releaseLease(String owner) {
        redis.execute(RELEASE, List.of(leaseKey), owner);
    }
}
