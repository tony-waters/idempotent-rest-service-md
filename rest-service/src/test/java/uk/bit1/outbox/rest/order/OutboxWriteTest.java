package uk.bit1.outbox.rest.order;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Spring Boot disables tracing in tests by default; @AutoConfigureTracing re-enables it here
// since this test asserts on a real, propagator-produced traceparent. The
// OTLP exporter is excluded since there's no collector in the test environment — otherwise
// every run logs an ERROR-level "connection refused" trying to reach localhost:4318.
@AutoConfigureTracing
@AutoConfigureTestRestTemplate
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration")
class OutboxWriteTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:8.0");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static HttpEntity<CreateOrderRequest> withIdempotencyKey(CreateOrderRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return new HttpEntity<>(request, headers);
    }

    @Test
    void writesAnOutboxRowInDebeziumsExpectedShape() {
        CreateOrderRequest request = new CreateOrderRequest("customer@example.com", new BigDecimal("19.99"));

        ResponseEntity<OrderResponse> createResponse = restTemplate.postForEntity("/orders", withIdempotencyKey(request), OrderResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse created = createResponse.getBody();
        assertThat(created).isNotNull();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT aggregatetype, aggregateid, type, payload, trace_context FROM outbox WHERE aggregateid = ?",
                created.id().toString());

        assertThat(row.get("aggregatetype")).isEqualTo("order");
        assertThat(row.get("aggregateid")).isEqualTo(created.id().toString());
        assertThat(row.get("type")).isEqualTo("OrderCreated");
        assertThat(row.get("payload").toString())
                .contains(created.id().toString())
                .contains("customer@example.com")
                .contains("19.99");
        // Full sampling is on (management.tracing.sampling.probability=1.0), so the request's
        // span must have been captured as a W3C traceparent (see ADR 0005): "00-<32 hex trace
        // id>-<16 hex span id>-<2 hex flags>".
        assertThat((String) row.get("trace_context")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    void noOutboxEventTypeOtherThanOrderCreatedIsEverWritten() {
        CreateOrderRequest request = new CreateOrderRequest("another@example.com", new BigDecimal("5.00"));
        restTemplate.postForEntity("/orders", withIdempotencyKey(request), OrderResponse.class);

        Long distinctTypes = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT type) FROM outbox", Long.class);
        assertThat(distinctTypes).isEqualTo(1L);

        String theOnlyType = jdbcTemplate.queryForObject("SELECT DISTINCT type FROM outbox", String.class);
        assertThat(theOnlyType).isEqualTo("OrderCreated");
    }
}
