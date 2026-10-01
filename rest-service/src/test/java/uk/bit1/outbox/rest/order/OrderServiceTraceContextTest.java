package uk.bit1.outbox.rest.order;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import io.micrometer.tracing.Tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// Covers the seam OrderService added for ADR 0005: when there's no current span (e.g. tracing
// disabled, or the write happens outside an instrumented request), the outbox row must still
// be written, with a null trace_context rather than a thrown exception or a fabricated value.
@Testcontainers
@SpringBootTest
class OrderServiceTraceContextTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:8.0");

    // A spy, not a bare mock: the real StringRedisTemplate bean also shares this app-wide Tracer
    // bean (Lettuce's own Micrometer tracing integration calls tracer.spanBuilder() on every
    // Redis command, now that IdempotencyAspect talks to Redis on this path), so unstubbed
    // methods must still delegate to the real Tracer rather than returning null.
    @MockitoSpyBean
    private Tracer tracer;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void writesANullTraceContextWhenThereIsNoCurrentSpan() {
        when(tracer.currentSpan()).thenReturn(null);

        Order order = orderService.createOrder("trace-context-test-key", new CreateOrderRequest("customer@example.com", new BigDecimal("19.99")));

        String traceContext = jdbcTemplate.queryForObject(
                "SELECT trace_context FROM outbox WHERE aggregateid = ?", String.class, order.getId().toString());
        assertThat(traceContext).isNull();
    }
}
