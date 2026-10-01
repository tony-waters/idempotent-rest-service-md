package com.example.outbox.email.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailServiceConsumptionTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void consumesAnOrderCreatedEventAndSendsAConfirmation() throws Exception {
        double before = emailsSent().count();

        kafkaTemplate.send(OrderCreatedListener.OUTBOX_ORDER_TOPIC, UUID.randomUUID().toString(), orderCreatedJson());

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(emailsSent().count()).isEqualTo(before + 1));

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/metrics/emails.sent", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"name\":\"emails.sent\"");
    }

    @Test
    void rateLimiterThrottlesABurstFasterThanFiveEventsPerTenSeconds() throws Exception {
        double before = emailsSent().count();
        int burst = 7;

        for (int i = 0; i < burst; i++) {
            kafkaTemplate.send(OrderCreatedListener.OUTBOX_ORDER_TOPIC, UUID.randomUUID().toString(), orderCreatedJson());
        }
        kafkaTemplate.flush();

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(emailsSent().count()).isLessThan(before + burst));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(emailsSent().count()).isEqualTo(before + burst));
    }

    private Counter emailsSent() {
        return meterRegistry.get("emails.sent").counter();
    }

    private String orderCreatedJson() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), "customer@example.com", new BigDecimal("19.99"), Instant.now());
        return objectMapper.writeValueAsString(event);
    }
}
