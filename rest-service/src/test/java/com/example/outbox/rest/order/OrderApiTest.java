package com.example.outbox.rest.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsAndReturnsAnOrder() {
        CreateOrderRequest request = new CreateOrderRequest("customer@example.com", new BigDecimal("19.99"));

        ResponseEntity<OrderResponse> createResponse = restTemplate.postForEntity("/orders", request, OrderResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.customerEmail()).isEqualTo("customer@example.com");
        assertThat(created.amount()).isEqualByComparingTo("19.99");
        assertThat(created.createdAt()).isNotNull();

        ResponseEntity<OrderResponse> getResponse = restTemplate.getForEntity("/orders/" + created.id(), OrderResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        OrderResponse fetched = getResponse.getBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.customerEmail()).isEqualTo(created.customerEmail());
        assertThat(fetched.amount()).isEqualByComparingTo(created.amount());
    }

    @Test
    void returnsNotFoundForUnknownOrder() {
        ResponseEntity<String> response = restTemplate.getForEntity("/orders/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
