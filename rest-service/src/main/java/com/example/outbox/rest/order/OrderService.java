package com.example.outbox.rest.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    public OrderService(OrderRepository orderRepository, OutboxEventRepository outboxEventRepository,
                         ObjectMapper objectMapper, Tracer tracer, Propagator propagator) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        Order order = new Order(UUID.randomUUID(), request.customerEmail(), request.amount(), Instant.now());
        orderRepository.save(order);

        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "order", order.getId().toString(), "OrderCreated",
                writePayload(order), currentTraceParent());
        outboxEventRepository.save(event);

        return order;
    }

    // Carries the current request's trace into the outbox row so it survives the CDC hop
    // (see ADR 0005) — delegates to the registered Propagator rather than hand-formatting a
    // W3C traceparent, so this keeps working if the propagation format ever changes.
    //
    // Injects a dedicated PRODUCER-kind span rather than the ambient HTTP SERVER span: Tempo's
    // service-graph processor only draws an edge between two services when a CLIENT/PRODUCER
    // span directly parents the far side's SERVER/CONSUMER span. email-service's Kafka listener
    // span is CONSUMER, so without this, the parent would be the SERVER span, no pairing would
    // match, and Grafana's service graph would show rest-service/email-service as disconnected
    // rather than the real call.
    private String currentTraceParent() {
        if (tracer.currentSpan() == null) {
            return null;
        }
        Span producerSpan = tracer.spanBuilder()
                .kind(Span.Kind.PRODUCER)
                .name("outbox.event.order publish")
                .start();
        try {
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(producerSpan.context(), carrier, Map::put);
            return carrier.get("traceparent");
        } finally {
            producerSpan.end();
        }
    }

    public Optional<Order> findOrder(UUID id) {
        return orderRepository.findById(id);
    }

    private String writePayload(Order order) {
        try {
            return objectMapper.writeValueAsString(OrderCreatedPayload.from(order));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OrderCreated payload for order " + order.getId(), e);
        }
    }
}
