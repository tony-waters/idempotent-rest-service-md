package com.example.outbox.rest.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Order() {
    }

    public Order(UUID id, String customerEmail, BigDecimal amount, Instant createdAt) {
        this.id = id;
        this.customerEmail = customerEmail;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
