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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Covers the two @Idempotent lock-lifecycle scenarios from ticket #6 that need to observe the
// lock mid-flight: a failed attempt releasing its key, and a concurrent duplicate while the
// first is still in progress. Reuses OrderOutboxAtomicityTest's @MockitoBean failure-injection
// technique on OutboxEventRepository, driven through the HTTP seam.
@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyFailureAndConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:8.0");

    @MockitoBean
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    private static HttpEntity<CreateOrderRequest> withKey(CreateOrderRequest request, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return new HttpEntity<>(request, headers);
    }

    @Test
    void aFailedRequestReleasesItsKeySoAnImmediateRetrySucceeds() {
        when(outboxEventRepository.save(any()))
                .thenThrow(new RuntimeException("simulated outbox write failure"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        String key = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest("retry@example.com", new BigDecimal("12.00"));
        long baseline = orderRepository.count();

        ResponseEntity<String> failedAttempt = restTemplate.postForEntity("/orders", withKey(request, key), String.class);
        assertThat(failedAttempt.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(orderRepository.count()).isEqualTo(baseline);

        ResponseEntity<OrderResponse> retry = restTemplate.postForEntity("/orders", withKey(request, key), OrderResponse.class);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(orderRepository.count()).isEqualTo(baseline + 1);
    }

    @Test
    void aConcurrentRequestWithTheSameKeyWhileTheFirstIsInFlightGetsTooEarly() throws Exception {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        when(outboxEventRepository.save(any())).thenAnswer(invocation -> {
            firstRequestStarted.countDown();
            if (!releaseFirstRequest.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the first request in time");
            }
            return invocation.getArgument(0);
        });
        String key = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest("concurrent@example.com", new BigDecimal("8.00"));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<ResponseEntity<OrderResponse>> firstCall = executor.submit(
                    () -> restTemplate.postForEntity("/orders", withKey(request, key), OrderResponse.class));
            assertThat(firstRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();

            ResponseEntity<String> secondCall = restTemplate.postForEntity("/orders", withKey(request, key), String.class);
            assertThat(secondCall.getStatusCode().value()).isEqualTo(425);

            releaseFirstRequest.countDown();
            ResponseEntity<OrderResponse> firstResult = firstCall.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        } finally {
            executor.shutdown();
        }
    }
}
