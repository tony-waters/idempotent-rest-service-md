package com.example.outbox.rest.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(UUID id, String customerEmail, BigDecimal amount, Instant createdAt) {

    static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getCustomerEmail(), order.getAmount(), order.getCreatedAt());
    }
}
