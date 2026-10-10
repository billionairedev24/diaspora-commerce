-- Atomic token bucket. KEYS[1] = bucket hash; ARGV[1] = capacity; ARGV[2] = refill tokens per millisecond.
-- Time comes from the Valkey server (TIME), so pods with skewed clocks share one consistent bucket.
-- Returns {allowed (0|1), whole tokens remaining, milliseconds until the next token (0 when allowed)}.
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end
if now > ts then
  tokens = math.min(capacity, tokens + (now - ts) * refill)
  ts = now
end
local allowed = 0
local retry = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.ceil((1 - tokens) / refill)
end
redis.call('HSET', KEYS[1], 'tokens', string.format('%.6f', tokens), 'ts', string.format('%d', ts))
-- A full bucket carries no information: let idle keys expire once they would have refilled.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill) + 1000)
return {allowed, math.floor(tokens), retry}
