package com.example.outbox.rest.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record OrderCreatedPayload(UUID orderId, String customerEmail, BigDecimal amount, Instant createdAt) {

    static OrderCreatedPayload from(Order order) {
        return new OrderCreatedPayload(order.getId(), order.getCustomerEmail(), order.getAmount(), order.getCreatedAt());
    }
}
