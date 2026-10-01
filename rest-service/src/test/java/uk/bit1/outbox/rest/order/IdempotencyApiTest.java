package uk.bit1.outbox.rest.order;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// Covers ticket #6 / ADR 0006's client-facing idempotency contract for POST /orders, exercised
// entirely through the HTTP seam per the ticket's testing approach.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:8.0");

    @Autowired
    private TestRestTemplate restTemplate;

    private static HttpEntity<CreateOrderRequest> withKey(CreateOrderRequest request, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return new HttpEntity<>(request, headers);
    }

    @Test
    void requiresAnIdempotencyKeyHeader() {
        CreateOrderRequest request = new CreateOrderRequest("customer@example.com", new BigDecimal("19.99"));

        ResponseEntity<String> response = restTemplate.postForEntity("/orders", withKey(request, null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void replayingTheSameKeyAndBodyReturnsTheOriginalOrderWithoutCreatingADuplicate() {
        String key = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest("customer@example.com", new BigDecimal("19.99"));

        ResponseEntity<OrderResponse> first = restTemplate.postForEntity("/orders", withKey(request, key), OrderResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse firstOrder = first.getBody();
        assertThat(firstOrder).isNotNull();

        ResponseEntity<OrderResponse> second = restTemplate.postForEntity("/orders", withKey(request, key), OrderResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse replayedOrder = second.getBody();
        assertThat(replayedOrder).isNotNull();
        assertThat(replayedOrder.id()).isEqualTo(firstOrder.id());
        assertThat(replayedOrder.customerEmail()).isEqualTo(firstOrder.customerEmail());
        assertThat(replayedOrder.amount()).isEqualByComparingTo(firstOrder.amount());
        // Not an exact Instant match: the replay is re-fetched from Postgres (per ADR 0006),
        // whose TIMESTAMPTZ column truncates to microseconds, while firstOrder's timestamp is
        // still the nanosecond-precision in-memory value from the original Instant.now() — the
        // same reason OrderApiTest doesn't compare createdAt across a create/fetch round trip.
        assertThat(replayedOrder.createdAt()).isCloseTo(firstOrder.createdAt(), within(1, java.time.temporal.ChronoUnit.MILLIS));

        ResponseEntity<OrderResponse> fetched = restTemplate.getForEntity("/orders/" + firstOrder.id(), OrderResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reusingTheSameKeyWithADifferentBodyReturnsConflict() {
        String key = UUID.randomUUID().toString();
        CreateOrderRequest original = new CreateOrderRequest("customer@example.com", new BigDecimal("19.99"));
        CreateOrderRequest changed = new CreateOrderRequest("customer@example.com", new BigDecimal("25.00"));

        ResponseEntity<OrderResponse> first = restTemplate.postForEntity("/orders", withKey(original, key), OrderResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = restTemplate.postForEntity("/orders", withKey(changed, key), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
