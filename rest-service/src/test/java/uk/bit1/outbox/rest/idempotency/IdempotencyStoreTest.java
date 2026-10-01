package uk.bit1.outbox.rest.idempotency;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Exercises IdempotencyStore's lock protocol directly against a real Redis — no Spring context,
// no HTTP, no AspectJ proxy — proving the CAS mechanics stand on their own. Candidate A of the
// architecture review split this out of IdempotencyAspect specifically so these scenarios no
// longer need a live HTTP round-trip to observe.
//
// Not covered here: forcing the exact intra-call race where a key vanishes between the
// setIfAbsent and the follow-up read inside a single tryBegin() invocation (the "continue" branch
// in IdempotencyStore.tryBegin), or MAX_LOCK_ATTEMPTS exhaustion — both require interleaving two
// Redis operations mid-method, which isn't observable from outside tryBegin() without a seam the
// grilling session deliberately chose not to introduce (see architecture review, candidate A, Q5).
// What IS covered is the equivalent externally-observable behaviour: a key that is absent —
// whether because it was just released, or because it vanished for any other reason — always
// allows a fresh acquire rather than getting stuck.
@Testcontainers
class IdempotencyStoreTest {

    @Container
    static RedisContainer redis = new RedisContainer("redis:8.0");

    private static StringRedisTemplate redisTemplate;
    private static IdempotencyStore store;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getFirstMappedPort()));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        IdempotencyProperties properties = new IdempotencyProperties();
        store = new IdempotencyStore(redisTemplate, JsonMapper.builder().build(), properties);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void aFreshKeyIsAcquired() {
        LockResult result = store.tryBegin("test.scope", newKey(), "fingerprint");

        assertThat(result).isInstanceOf(LockResult.Acquired.class);
    }

    @Test
    void aKeyStillInProgressRejectsAConcurrentAttempt() {
        String key = newKey();
        store.tryBegin("test.scope", key, "fingerprint");

        LockResult result = store.tryBegin("test.scope", key, "fingerprint");

        assertThat(result).isInstanceOf(LockResult.InProgress.class);
    }

    @Test
    void aCompletedKeyWithTheSameFingerprintIsReplayable() {
        String key = newKey();
        UUID resultId = UUID.randomUUID();
        store.tryBegin("test.scope", key, "fingerprint");
        store.complete("test.scope", key, "fingerprint", resultId);

        LockResult result = store.tryBegin("test.scope", key, "fingerprint");

        assertThat(result).isEqualTo(new LockResult.Replayable(resultId));
    }

    @Test
    void aCompletedKeyWithADifferentFingerprintIsAConflict() {
        String key = newKey();
        store.tryBegin("test.scope", key, "fingerprint-a");
        store.complete("test.scope", key, "fingerprint-a", UUID.randomUUID());

        LockResult result = store.tryBegin("test.scope", key, "fingerprint-b");

        assertThat(result).isInstanceOf(LockResult.Conflict.class);
    }

    @Test
    void releasingAKeyAllowsAFreshAcquire() {
        String key = newKey();
        store.tryBegin("test.scope", key, "fingerprint");

        store.release("test.scope", key);
        LockResult result = store.tryBegin("test.scope", key, "fingerprint");

        assertThat(result).isInstanceOf(LockResult.Acquired.class);
    }

    @Test
    void aKeyThatVanishesWithoutAnExplicitReleaseAlsoAllowsAFreshAcquire() {
        String key = newKey();
        store.tryBegin("test.scope", key, "fingerprint");

        // Simulates the key vanishing for a reason other than our own release() — e.g. TTL expiry,
        // or Redis-side eviction — by deleting it directly rather than through the store's API.
        redisTemplate.delete(store.keyFor("test.scope", key));

        LockResult result = store.tryBegin("test.scope", key, "fingerprint");

        assertThat(result).isInstanceOf(LockResult.Acquired.class);
    }

    @Test
    void completingARequestStoresTheRecordNamespacedByScopeWithTheConfiguredTtl() {
        String key = newKey();
        store.tryBegin("order.scope", key, "fingerprint");

        store.complete("order.scope", key, "fingerprint", UUID.randomUUID());

        Long ttlSeconds = redisTemplate.getExpire(store.keyFor("order.scope", key), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isGreaterThan(Duration.ofHours(23).toSeconds());
        assertThat(ttlSeconds).isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());
    }
}
