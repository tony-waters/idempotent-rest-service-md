package com.example.outbox.email.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Proves the crux of ADR 0005: a consumed record carrying Debezium's routed "traceparent"
// header continues that trace (same trace ID) rather than starting a new root span.
// Spring Boot disables tracing in tests by default (ObservabilityContextCustomizerFactory);
// @AutoConfigureObservability re-enables it so the real Propagator/Tracer wiring runs. The
// OTLP exporter is excluded since there's no collector in the test environment — otherwise
// every run logs an ERROR-level "connection refused" trying to reach localhost:4318.
@AutoConfigureObservability
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration")
@Import(OrderCreatedTracePropagationTest.TestTracingConfig.class)
class OrderCreatedTracePropagationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void resetSpans() {
        spanExporter.reset();
    }

    @Test
    void continuesTheTraceCarriedByTheTraceparentHeader() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";

        ProducerRecord<String, String> record = new ProducerRecord<>(
                OrderCreatedListener.OUTBOX_ORDER_TOPIC, UUID.randomUUID().toString(), orderCreatedJson());
        record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).isNotEmpty();
            assertThat(spans).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(traceId));
        });
    }

    private String orderCreatedJson() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), "customer@example.com", new BigDecimal("19.99"), Instant.now());
        return objectMapper.writeValueAsString(event);
    }

    @TestConfiguration
    static class TestTracingConfig {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }
}
