package com.example.outbox.email.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record OrderCreatedEvent(UUID orderId, String customerEmail, BigDecimal amount, Instant createdAt) {
}
