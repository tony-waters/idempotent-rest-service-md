package uk.bit1.outbox.rest.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

// Owns the Redis-backed CAS lock protocol behind @Idempotent: acquiring a key, retrying past a
// vanished key, and recording completion or conflict. Knows nothing about AspectJ, HTTP, or how a
// resultId turns back into a response — see IdempotencyAspect for that. See ADR 0006.
@Component
class IdempotencyStore {

    private static final int MAX_LOCK_ATTEMPTS = 2;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;

    IdempotencyStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, IdempotencyProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    String keyFor(String scope, String key) {
        return "idempotency:" + scope + ":" + key;
    }

    LockResult tryBegin(String scope, String key, String fingerprint) {
        String redisKey = keyFor(scope, key);
        for (int attempt = 1; attempt <= MAX_LOCK_ATTEMPTS; attempt++) {
            boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    redisKey, write(new IdempotencyRecord(IdempotencyRecord.Status.IN_PROGRESS, fingerprint, null)), properties.getTtl()));
            if (acquired) {
                return new LockResult.Acquired();
            }

            IdempotencyRecord existing = read(redisTemplate.opsForValue().get(redisKey));
            if (existing == null) {
                // Key vanished between the setIfAbsent above and this read (expired, or deleted by
                // a concurrent failed attempt): retry the atomic acquisition rather than proceeding
                // without a lock.
                continue;
            }
            if (existing.status() == IdempotencyRecord.Status.IN_PROGRESS) {
                return new LockResult.InProgress();
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                return new LockResult.Conflict();
            }
            return new LockResult.Replayable(existing.resultId());
        }
        throw new IllegalStateException("Could not acquire or read idempotency state for " + redisKey + " after " + MAX_LOCK_ATTEMPTS + " attempts");
    }

    void complete(String scope, String key, String fingerprint, UUID resultId) {
        redisTemplate.opsForValue().set(keyFor(scope, key),
                write(new IdempotencyRecord(IdempotencyRecord.Status.COMPLETED, fingerprint, resultId)), properties.getTtl());
    }

    void release(String scope, String key) {
        redisTemplate.delete(keyFor(scope, key));
    }

    private String write(IdempotencyRecord record) {
        return objectMapper.writeValueAsString(record);
    }

    private IdempotencyRecord read(String json) {
        return json == null ? null : objectMapper.readValue(json, IdempotencyRecord.class);
    }
}
